package com.vivo.lanxin.campus.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Rule rule = ruleFor(request.getRequestURI());
        if (rule == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        long now = clock.millis();
        String key = clientIp(request) + ":" + rule.name();
        Bucket bucket = buckets.compute(key, (ignored, current) -> {
            if (current == null || now >= current.windowEndsAt()) {
                return new Bucket(now + rule.windowMillis(), new AtomicInteger(1));
            }
            current.count().incrementAndGet();
            return current;
        });

        if (bucket.count().get() > rule.limit()) {
            throw new RateLimitException("请求过于频繁，请稍后再试");
        }
        cleanup(now);
        return true;
    }

    private Rule ruleFor(String uri) {
        if (uri.endsWith("/user/login") || uri.endsWith("/user/register")) {
            return new Rule("auth", 10, 60_000);
        }
        if (uri.contains("/ai/") || uri.contains("/rag/chat") || uri.endsWith("/reminders/parse")) {
            return new Rule("ai", 30, 60_000);
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void cleanup(long now) {
        if (buckets.size() < 10_000) {
            return;
        }
        buckets.entrySet().removeIf(entry -> now >= entry.getValue().windowEndsAt());
    }

    private record Rule(String name, int limit, long windowMillis) {}

    private record Bucket(long windowEndsAt, AtomicInteger count) {}
}
