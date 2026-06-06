package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TextRequest(@NotBlank @Size(max = 2_000) String text) {}
