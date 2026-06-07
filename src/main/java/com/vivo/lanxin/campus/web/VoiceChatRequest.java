package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for text-to-speech and voice-chat endpoints.
 */
public record VoiceChatRequest(
        @NotBlank(message = "文字内容不能为空")
        String text,

        @Size(max = 50, message = "音色名称最长50个字符")
        String voice
) {}
