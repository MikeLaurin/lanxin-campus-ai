package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(
                request.username(),
                request.password(),
                request.name(),
                request.school(),
                request.major(),
                request.grade()
        );
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        auth.logout(authHeader);
        return Map.of("ok", true);
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        Map<String, Object> info = auth.getUserInfo(userId);
        info.put("slogan", "你的校园学习搭子，更懂你的 AI 管家");
        return info;
    }
}
