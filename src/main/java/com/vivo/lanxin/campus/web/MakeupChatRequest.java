package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MakeupChatRequest(
        @NotBlank @Size(max = 50_000) String makeupContent,
        @NotBlank @Size(max = 4_000) String message
) {}
