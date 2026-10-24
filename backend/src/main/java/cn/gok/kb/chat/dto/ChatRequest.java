package cn.gok.kb.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question       本轮问题
 * @param topK           注入模型的片段数，不传用配置默认值
 * @param conversationId 会话 id。首轮不传，后端生成后随响应返回；
 *                       后续追问带上它，后端才能接上多轮上下文并做检索改写
 */
public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题请控制在 500 字以内")
        String question,

        Integer topK,

        @Size(max = 64, message = "会话 id 不合法")
        String conversationId
) {
}
