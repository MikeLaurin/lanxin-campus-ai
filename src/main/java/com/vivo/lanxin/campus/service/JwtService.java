package com.vivo.lanxin.campus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivo.lanxin.campus.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${app.jwt.secret:${APP_JWT_SECRET:lanxin-campus-ai-dev-secret-change-me}}") String secret,
            @Value("${app.jwt.access-token-minutes:120}") long accessTokenMinutes,
            @Value("${app.jwt.refresh-token-days:7}") long refreshTokenDays
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenSeconds = accessTokenMinutes * 60;
        this.refreshTokenSeconds = refreshTokenDays * 24 * 60 * 60;
    }

    public TokenPair issueTokens(long userId, String username) {
        Instant now = Instant.now();
        return new TokenPair(
                sign(userId, username, "access", now.plusSeconds(accessTokenSeconds)),
                sign(userId, username, "refresh", now.plusSeconds(refreshTokenSeconds)),
                now.plusSeconds(accessTokenSeconds).getEpochSecond()
        );
    }

    public Claims verifyAccessToken(String token) {
        return verify(token, "access");
    }

    public Claims verifyRefreshToken(String token) {
        return verify(token, "refresh");
    }

    private String sign(long userId, String username, String type, Instant expiresAt) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", String.valueOf(userId));
            payload.put("username", username);
            payload.put("type", type);
            payload.put("exp", expiresAt.getEpochSecond());
            payload.put("iat", Instant.now().getEpochSecond());

            String unsigned = base64Url(objectMapper.writeValueAsBytes(header))
                    + "." + base64Url(objectMapper.writeValueAsBytes(payload));
            return unsigned + "." + base64Url(hmac(unsigned));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "JWT_SIGN_FAILED", "令牌生成失败");
        }
    }

    private Claims verify(String token, String expectedType) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw unauthorized("登录令牌格式无效");
            }
            String unsigned = parts[0] + "." + parts[1];
            String expectedSignature = base64Url(hmac(unsigned));
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                throw unauthorized("登录令牌签名无效");
            }
            Map<String, Object> payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[1]), MAP_TYPE);
            String type = String.valueOf(payload.get("type"));
            if (!expectedType.equals(type)) {
                throw unauthorized("登录令牌类型无效");
            }
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw unauthorized("登录已过期，请重新登录");
            }
            long userId = Long.parseLong(String.valueOf(payload.get("sub")));
            String username = String.valueOf(payload.getOrDefault("username", ""));
            return new Claims(userId, username, exp);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("登录令牌解析失败");
        }
    }

    private byte[] hmac(String value) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresAt) {}

    public record Claims(long userId, String username, long expiresAt) {}
}
