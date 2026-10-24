package cn.gok.kb.chat.service;

import cn.gok.kb.config.KbProperties;
import cn.gok.kb.search.dto.SearchHitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把检索到的片段拼成上下文，交给模型生成答案。
 *
 * <p>防幻觉靠三层：
 * <ol>
 *   <li>提示词里明确「只依据参考资料、不许用自身知识补充」；</li>
 *   <li>检索没命中时**根本不调用模型**，直接回「查不到」，不给它瞎编的机会；</li>
 *   <li>答案里必须带 [n] 编号，编号能对回具体的文件和页码，用户可自行核对。</li>
 * </ol>
 */
@Slf4j
@Service
public class AiAnswerService {

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。你只能依据用户提供的【参考资料】作答。

            必须遵守：
            1. 参考资料里没有的信息一律不回答。资料不足时直接说「根据现有资料无法回答该问题」，并说明还缺哪方面的资料。
            2. 不允许用你自己的常识或推测补充细节，包括数字、时间、人名、结论。
            3. 每处引用了资料的内容，在句末标注来源编号，例如 [1]；多处引用写成 [1][3]。
            4. 使用简体中文，分点作答，总字数控制在 300 字以内。
            5. 如果资料之间互相矛盾，指出矛盾点，不要自行替用户做判断。
            """;

    /** 单个片段注入前截断长度，防止上下文被一个长切片占满 */
    private static final int MAX_SNIPPET = 600;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int maxContextChunks;

    public AiAnswerService(ObjectProvider<ChatClient.Builder> builderProvider, KbProperties props) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.maxContextChunks = props.getAi().getMaxContextChunks();
        this.enabled = props.getAi().isEnabled() && builder != null;
        this.chatClient = enabled ? builder.defaultSystem(SYSTEM_PROMPT).build() : null;
        log.info("模型问答：{}", enabled ? "已启用" : "未启用（降级为纯检索模式）");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return 模型生成的答案；调用失败返回 null，由上层降级
     */
    public String answer(String question, List<SearchHitVO> hits) {
        if (!enabled || hits.isEmpty()) {
            return null;
        }
        try {
            String userPrompt = buildPrompt(question, hits);
            String answer = chatClient.prompt().user(userPrompt).call().content();
            if (answer == null || answer.isBlank()) {
                log.warn("模型返回空内容");
                return null;
            }
            return answer.trim();
        } catch (Exception e) {
            // 模型超时 / 欠费 / Key 失效都走这里，不能让整个问答接口 500
            log.warn("模型调用失败，降级为纯检索：{}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question, List<SearchHitVO> hits) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("【参考资料】\n");
        int limit = Math.min(hits.size(), Math.max(1, maxContextChunks));
        for (int i = 0; i < limit; i++) {
            SearchHitVO h = hits.get(i);
            String content = h.content();
            if (content.length() > MAX_SNIPPET) {
                content = content.substring(0, MAX_SNIPPET) + "…";
            }
            sb.append('[').append(i + 1).append("] 来源：《").append(h.fileName())
                    .append("》第 ").append(h.pageNo()).append(" 页\n")
                    .append(content).append("\n\n");
        }
        sb.append("【问题】\n").append(question);
        return sb.toString();
    }
}
