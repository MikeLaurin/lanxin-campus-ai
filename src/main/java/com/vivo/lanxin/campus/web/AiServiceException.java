package com.vivo.lanxin.campus.web;

import org.springframework.http.HttpStatus;

public class AiServiceException extends ApiException {
    private final Integer aiStatus;
    private final String provider;

    public AiServiceException(Integer aiStatus, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_UNAVAILABLE", message);
        this.aiStatus = aiStatus;
        this.provider = "lanxin";
    }

    public Integer getAiStatus() {
        return aiStatus;
    }

    public String getProvider() {
        return provider;
    }
}
