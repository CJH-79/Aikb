package cn.gok.kb.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题请控制在 500 字以内")
        String question,

        /** 注入模型的片段数，不传用配置默认值 */
        Integer topK
) {
}
