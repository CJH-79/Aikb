package cn.gok.kb.chat.controller;

import cn.gok.kb.chat.dto.ChatRequest;
import cn.gok.kb.chat.dto.ChatVO;
import cn.gok.kb.chat.service.ChatService;
import cn.gok.kb.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 同步返回。答案生成通常 2~10 秒，前端用 loading 态承接；
     * 之所以不用 SSE：单轮问答没有增量输出的必要，同步接口出错反而更好定位。
     */
    @PostMapping
    public Result<ChatVO> ask(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chatService.ask(request.question(), request.topK()));
    }
}
