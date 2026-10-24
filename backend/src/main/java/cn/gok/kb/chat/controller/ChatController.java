package cn.gok.kb.chat.controller;

import cn.gok.kb.chat.dto.ChatRequest;
import cn.gok.kb.chat.dto.ChatVO;
import cn.gok.kb.chat.service.ChatService;
import cn.gok.kb.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 同步返回，一次性拿到完整答案。
     * 保留它是因为调试和脚本调用方便，线上走 {@link #stream}。
     */
    @PostMapping
    public Result<ChatVO> ask(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chatService.ask(request.question(), request.topK(), request.conversationId()));
    }

    /**
     * SSE 流式问答。
     *
     * <p>用 POST 而不是 EventSource 的 GET：EventSource 不支持自定义请求体，
     * 而我们既要把问题放进 body（避免超长 URL 编码问题），又要带 conversationId。
     * 所以前端用 fetch + ReadableStream 自己解析 SSE 帧，协议本身不变。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return chatService.askStream(request.question(), request.topK(), request.conversationId());
    }

    /** 清空某个会话的多轮记忆，用于前端「新开会话」 */
    @PostMapping("/reset")
    public Result<Void> reset(@RequestParam String conversationId) {
        chatService.reset(conversationId);
        return Result.ok();
    }
}
