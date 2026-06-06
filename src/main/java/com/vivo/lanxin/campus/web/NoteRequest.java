package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NoteRequest(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 80) String course,
        @Size(max = 200) String folderPath,
        @Size(max = 20_000) String rawText,
        @Size(max = 4_000) String summary,
        List<String> keyPoints,
        List<String> formulas,
        List<String> tags,
        @Size(max = 6_000) String mindMap,
        boolean offlineCreated
) {}
