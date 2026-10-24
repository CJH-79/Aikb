package cn.gok.kb.chat.service;

import cn.gok.kb.config.KbProperties;
import cn.gok.kb.search.dto.SearchHitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
 *
 * <p>多轮的处理方式有个容易踩的坑值得记下来：
 * 记忆（{@link MessageChatMemoryAdvisor}）加的是**对话历史**，
 * 但参考资料是**每轮重新检索出来的**，两者是两套东西。
 * 如果只加记忆不加检索改写，用户在追问「那出差呢」时，
 * 模型能看到上文却拿不到出差相关的切片，只能靠编——
 * 这比完全没有多轮更危险。所以检索改写和记忆必须成对出现。
 *
 * <p>另一个更隐蔽的坑：<b>参考资料必须放 system 消息，不能拼进 user 消息</b>。
 * 因为 advisor 记录的是「请求里的 user 消息」，如果 user 消息是
 * 「【参考资料】……【问题】……」，那存进记忆的就是这一整坨，
 * 于是出现两个后果：① 记忆窗口每条都膨胀到上千字，几轮就把上下文塞满；
 * ② 下一轮做检索改写时，取到的「上一轮问题」其实是上一轮的整段 prompt。
 * 把资料移到 system、user 只留用户原话，两个问题一起消失。
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
            6. 用户可能是追问（例如「那出差呢」）。你可以结合上文理解他问的是什么，
               但答案依据仍然只能来自本轮【参考资料】，历史对话不能当作事实来源。
            """;

    /** 单片段注入前截断长度，防止上下文被一个长切片占满 */
    private static final int MAX_SNIPPET = 600;

    private final ChatClient chatClient;
    private final ConversationStore conversationStore;
    private final boolean enabled;
    private final int maxContextChunks;

    public AiAnswerService(ObjectProvider<ChatClient.Builder> builderProvider,
                           ConversationStore conversationStore,
                           KbProperties props) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.conversationStore = conversationStore;
        this.maxContextChunks = props.getAi().getMaxContextChunks();
        this.enabled = props.getAi().isEnabled() && builder != null;
        // 注意：这里不设 defaultSystem。每轮的参考资料不同，system 消息要按请求拼，
        // 见 buildSystemPrompt()
        this.chatClient = enabled ? builder.build() : null;
        log.info("模型问答：{}", enabled ? "已启用" : "未启用（降级为纯检索模式）");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 同步生成。
     *
     * @return 模型生成的答案；调用失败返回 null，由上层降级
     */
    public String answer(String question, List<SearchHitVO> hits, String conversationId) {
        if (!enabled || hits.isEmpty()) {
            return null;
        }
        try {
            String answer = chatClient.prompt()
                    .system(buildSystemPrompt(hits))
                    .user(question)
                    .advisors(memoryAdvisor(conversationId))
                    .call()
                    .content();
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

    /**
     * 流式生成。模型不可用时返回 null，由上层决定降级方式。
     *
     * <p>流式的价值不只是「看起来快」：答案首字延迟从「整段生成完」缩短到
     * 「第一个 token」，用户能立刻判断答案方向对不对，不用干等十几秒。
     */
    public Flux<String> stream(String question, List<SearchHitVO> hits, String conversationId) {
        if (!enabled || hits.isEmpty()) {
            return null;
        }
        return chatClient.prompt()
                .system(buildSystemPrompt(hits))
                .user(question)
                .advisors(memoryAdvisor(conversationId))
                .stream()
                .content();
    }

    /**
     * 每次请求构造一个带 conversationId 的 advisor。
     *
     * <p>不复用单例 advisor 的原因：conversationId 是请求级变量，
     * 而 ChatClient 是应用级单例，共享一个绑死 id 的 advisor 会让所有会话串味。
     */
    private MessageChatMemoryAdvisor memoryAdvisor(String conversationId) {
        return MessageChatMemoryAdvisor.builder(conversationStore.memory())
                .conversationId(conversationId == null || conversationId.isBlank()
                        ? ChatMemory.DEFAULT_CONVERSATION_ID : conversationId)
                .build();
    }

    /** 规则 + 本轮参考资料，一起作为 system 消息 */
    private String buildSystemPrompt(List<SearchHitVO> hits) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(SYSTEM_PROMPT).append("\n【参考资料】\n");

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
        return sb.toString();
    }
}
