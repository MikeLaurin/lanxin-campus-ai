package com.vivo.lanxin.campus.web;

import org.springframework.http.HttpStatus;

public class RateLimitException extends ApiException {
    public RateLimitException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }
}
