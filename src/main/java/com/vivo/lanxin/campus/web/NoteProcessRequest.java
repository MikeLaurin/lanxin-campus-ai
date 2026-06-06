package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.Size;

public record NoteProcessRequest(
        @Size(max = 20_000) String rawText,
        boolean offline
) {}
