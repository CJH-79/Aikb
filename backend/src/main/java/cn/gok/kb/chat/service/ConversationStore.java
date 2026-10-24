package cn.gok.kb.chat.service;

import cn.gok.kb.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话状态。
 *
 * <p>两件事放在一起，因为它们服务于同一个目的——让追问能接上上文：
 * <ol>
 *   <li>对话记忆：交给 Spring AI 的 {@link MessageWindowChatMemory}，
 *       按 conversationId 存消息窗口，只保留最近 N 条，防止上下文无限膨胀；</li>
 *   <li>检索改写：多轮场景下真正难的不是「把历史消息喂给模型」，
 *       而是「拿什么去检索」。用户问「那出差呢」，
 *       这一句直接进 BM25 什么都搜不到，必须先结合上一轮问题补全。</li>
 * </ol>
 *
 * <p><b>这里的改写是规则式的，不是模型改写。</b>
 * 判断依据是「短问题 + 含指代词/以『那』『还』开头」就认为它是追问。
 * 好处是零额外模型调用、延迟和成本都不增加；局限是语义跳跃的追问识别不出来
 * （比如上一轮聊报销，这一轮突然问「审批人呢」）。
 * 要更准就得上一轮模型改写，那是另一笔延迟和费用，当前规模不值当。
 */
@Slf4j
@Service
public class ConversationStore {

    /** 出现这些词、且问题本身很短，就当作追问 */
    private static final List<String> ANAPHORA = List.of(
            "它", "他", "这个", "那个", "这些", "那些", "这种", "那种",
            "上面", "上述", "刚才", "前面", "该", "呢");

    /** 超过这个长度的问题一般自带主语和上下文，不做改写 */
    private static final int SHORT_QUESTION = 15;

    private final ChatMemory chatMemory;

    public ConversationStore(KbProperties props) {
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(Math.max(2, props.getAi().getMemoryMaxMessages()))
                .build();
        log.info("多轮对话已启用，单会话最多保留 {} 条消息", props.getAi().getMemoryMaxMessages());
    }

    public ChatMemory memory() {
        return chatMemory;
    }

    public String newConversationId() {
        return "c-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 本轮之前用户问的最后一句话；首轮对话返回 null */
    public String lastUserQuestion(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        List<Message> messages = chatMemory.get(conversationId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }

    /**
     * 生成真正拿去检索的查询串。
     *
     * <p>注意调用时机：必须在本次请求进入模型之前调用，
     * 否则当前问题已经被 advisor 写进记忆，拿到的「上一轮」就是自己。
     */
    public String buildRetrievalQuery(String conversationId, String question) {
        String prev = lastUserQuestion(conversationId);
        if (prev == null || prev.isBlank()) {
            return question;
        }
        if (question.length() >= SHORT_QUESTION) {
            return question;
        }
        boolean followUp = question.startsWith("那") || question.startsWith("还");
        if (!followUp) {
            for (String a : ANAPHORA) {
                if (question.contains(a)) {
                    followUp = true;
                    break;
                }
            }
        }
        if (!followUp) {
            return question;
        }
        // 上一轮问题在前、本轮在后：让 BM25 的命中词覆盖两轮，同时保留本轮的关键词权重
        String rewritten = prev + " " + question;
        log.debug("检测到追问，检索查询改写为：{}", rewritten);
        return rewritten;
    }

    public int messageCount(String conversationId) {
        return conversationId == null ? 0 : chatMemory.get(conversationId).size();
    }

    public void clear(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            chatMemory.clear(conversationId);
        }
    }
}
