package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.Size;

public record MakeupRequest(@Size(max = 80) String course) {}
