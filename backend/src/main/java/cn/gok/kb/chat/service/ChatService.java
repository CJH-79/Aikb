package cn.gok.kb.chat.service;

import cn.gok.kb.chat.dto.ChatVO;
import cn.gok.kb.search.dto.SearchHitVO;
import cn.gok.kb.search.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答编排：先检索，再决定是调模型还是降级。
 *
 * <p>两种出口共用同一套检索和降级逻辑：
 * {@link #ask} 同步一次性返回（curl / 调试友好），{@link #askStream} 走 SSE 逐字推送（前端用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String NO_HIT_TEXT =
            "知识库里没有检索到与该问题相关的内容。可以换个说法再问，或者先上传相关资料。";

    private static final String TIP_RETRIEVAL =
            "当前为纯检索模式：未配置模型或模型调用失败，以下是资料原文摘录";

    /** SSE 连接的最长存活时间。不能设成 0（永不超时）——那正是连接泄漏的经典来源 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final SearchService searchService;
    private final AiAnswerService aiAnswerService;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 同步

    public ChatVO ask(String question, Integer topK, String conversationId) {
        long start = System.currentTimeMillis();
        String cid = resolveConversationId(conversationId);

        // 注意顺序：检索改写在进入模型之前调用，否则当前问题已被写入记忆，取到的"上一轮"就是自己
        String retrievalQuery = conversationStore.buildRetrievalQuery(cid, question);
        List<SearchHitVO> hits = searchService.search(retrievalQuery, topK);

        if (hits.isEmpty()) {
            return new ChatVO(NO_HIT_TEXT, "empty", List.of(), cost(start),
                    "检索无命中，未调用模型", cid);
        }

        List<ChatVO.Citation> citations = toCitations(hits);

        // 给模型的仍是用户原话，改写过的那串只用于检索
        if (aiAnswerService.isEnabled()) {
            String answer = aiAnswerService.answer(question, hits, cid);
            if (answer != null) {
                return new ChatVO(answer, "llm", citations, cost(start), null, cid);
            }
        }

        return new ChatVO(buildRetrievalAnswer(hits), "retrieval", citations, cost(start),
                TIP_RETRIEVAL, cid);
    }

    // ------------------------------------------------------------------ 流式

    /**
     * 事件约定（前端按 name 分发）：
     * <pre>
     *   meta  {"conversationId","mode","citations","query","elapsedMs","tip"}
     *   delta {"t":"增量文本"}
     *   done  {"mode","elapsedMs","tip"}
     * </pre>
     * meta 先到，前端可以立刻把引用来源渲染出来，再等正文逐字出现。
     */
    public SseEmitter askStream(String question, Integer topK, String conversationId) {
        long start = System.currentTimeMillis();
        String cid = resolveConversationId(conversationId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        String retrievalQuery;
        List<SearchHitVO> hits;
        try {
            retrievalQuery = conversationStore.buildRetrievalQuery(cid, question);
            hits = searchService.search(retrievalQuery, topK);
        } catch (Exception e) {
            log.error("检索阶段失败", e);
            emit(emitter, "meta", mapOf("conversationId", cid, "mode", "error"));
            emit(emitter, "delta", mapOf("t", "检索失败：" + e.getMessage()));
            emit(emitter, "done", mapOf("mode", "error", "elapsedMs", cost(start)));
            emitter.complete();
            return emitter;
        }

        if (hits.isEmpty()) {
            emit(emitter, "meta", mapOf("conversationId", cid, "mode", "empty",
                    "citations", List.of(), "query", retrievalQuery,
                    "elapsedMs", cost(start), "tip", "检索无命中，未调用模型"));
            emit(emitter, "delta", mapOf("t", NO_HIT_TEXT));
            emit(emitter, "done", mapOf("mode", "empty", "elapsedMs", cost(start)));
            emitter.complete();
            return emitter;
        }

        List<ChatVO.Citation> citations = toCitations(hits);
        emit(emitter, "meta", mapOf("conversationId", cid,
                "mode", aiAnswerService.isEnabled() ? "llm" : "retrieval",
                "citations", citations,
                "query", retrievalQuery,
                "elapsedMs", cost(start)));

        Flux<String> flux = aiAnswerService.stream(question, hits, cid);
        if (flux == null) {
            // 模型未启用：直接把检索原文一次性推给前端，保持和流式一致的协议
            emit(emitter, "delta", mapOf("t", buildRetrievalAnswer(hits)));
            emit(emitter, "done", mapOf("mode", "retrieval", "elapsedMs", cost(start),
                    "tip", TIP_RETRIEVAL));
            emitter.complete();
            return emitter;
        }

        StringBuilder acc = new StringBuilder();
        AtomicBoolean closed = new AtomicBoolean(false);

        Disposable disposable = flux.subscribe(
                chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    acc.append(chunk);
                    emit(emitter, "delta", mapOf("t", chunk));
                },
                error -> {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    log.warn("流式生成失败：{}", error.getMessage());
                    finishWithFallback(emitter, hits, acc, start, "模型调用失败，已降级为纯检索");
                },
                () -> {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    if (acc.isEmpty()) {
                        // 模型返回了空流，等同于失败
                        finishWithFallback(emitter, hits, acc, start, "模型返回空内容，已降级为纯检索");
                    } else {
                        emit(emitter, "done", mapOf("mode", "llm", "elapsedMs", cost(start)));
                        emitter.complete();
                    }
                }
        );

        // 客户端断开或超时都要主动取消上游订阅，否则模型还在生成、token 还在烧
        emitter.onTimeout(() -> {
            log.debug("SSE 超时，取消上游订阅");
            disposable.dispose();
        });
        emitter.onError(e -> disposable.dispose());
        emitter.onCompletion(disposable::dispose);

        return emitter;
    }

    private void finishWithFallback(SseEmitter emitter, List<SearchHitVO> hits,
                                    StringBuilder acc, long start, String tip) {
        if (acc.isEmpty()) {
            emit(emitter, "delta", mapOf("t", buildRetrievalAnswer(hits)));
            emit(emitter, "done", mapOf("mode", "retrieval", "elapsedMs", cost(start), "tip", tip));
        } else {
            // 已经吐了一半的内容，就不再补检索原文，避免答案前后重复
            emit(emitter, "done", mapOf("mode", "llm", "elapsedMs", cost(start), "tip", "生成中断：" + tip));
        }
        emitter.complete();
    }

    // ------------------------------------------------------------------ 工具

    public void reset(String conversationId) {
        conversationStore.clear(conversationId);
    }

    private String resolveConversationId(String conversationId) {
        return (conversationId == null || conversationId.isBlank())
                ? conversationStore.newConversationId() : conversationId;
    }

    private List<ChatVO.Citation> toCitations(List<SearchHitVO> hits) {
        List<ChatVO.Citation> list = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            SearchHitVO h = hits.get(i);
            list.add(new ChatVO.Citation(i + 1, h.fileName(), h.fileType(), h.pageNo(),
                    brief(h.content()), h.score(), h.recall()));
        }
        return list;
    }

    /** 没有模型时的兜底文案：把最相关的几条原文按编号列出来 */
    private String buildRetrievalAnswer(List<SearchHitVO> hits) {
        StringBuilder sb = new StringBuilder("在知识库中找到以下相关内容：\n\n");
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            SearchHitVO h = hits.get(i);
            sb.append('[').append(i + 1).append("] ");
            sb.append(brief(h.content())).append('\n');
            sb.append("    ——《").append(h.fileName()).append("》第 ").append(h.pageNo()).append(" 页\n\n");
        }
        return sb.toString().trim();
    }

    private String brief(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private long cost(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 推送一个 SSE 事件。
     *
     * <p>这里手动把对象序列化成 JSON 字符串再发，而不是直接塞对象：
     * 响应头是 text/event-stream，让消息转换器去猜类型容易出岔子，
     * 转成字符串走 StringHttpMessageConverter 是确定行为。
     */
    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            // 客户端关掉页面就会走到这里，属于正常现象
            log.debug("SSE 推送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /** Map.of 不接受 null 值，这里统一过滤掉，省得每个调用点都判空 */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return map;
    }
}
