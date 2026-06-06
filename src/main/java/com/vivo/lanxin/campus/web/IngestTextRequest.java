package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;

public record IngestTextRequest(
        @NotBlank String title,
        @NotBlank String originalFilename,
        @NotBlank String fileType,
        @NotBlank String fullText
) {}
