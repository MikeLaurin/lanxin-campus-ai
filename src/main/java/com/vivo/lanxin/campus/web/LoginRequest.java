package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9_\\-]{3,50}$") String username,
        @NotBlank @Size(min = 6, max = 72) String password
) {}
