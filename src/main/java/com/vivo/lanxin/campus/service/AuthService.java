package com.vivo.lanxin.campus.service;

import com.vivo.lanxin.campus.model.UserEntity;
import com.vivo.lanxin.campus.repository.UserRepository;
import com.vivo.lanxin.campus.web.ApiException;
import com.vivo.lanxin.campus.web.InputSanitizer;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<Long, CachedUserInfo> userInfoCache = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostConstruct
    void seedDemoUser() {
        if (!userRepository.existsByUsername("demo")) {
            UserEntity user = new UserEntity();
            user.setUsername("demo");
            user.setPassword(encoder.encode("demo123"));
            user.setName("蓝心同学");
            user.setSchool("未来大学");
            user.setMajor("计算机科学与技术");
            user.setGrade("大二");
            userRepository.save(user);
        }
    }

    public Map<String, Object> login(String username, String password) {
        username = InputSanitizer.clean(username, 50);
        Optional<UserEntity> opt = userRepository.findByUsername(username);
        if (opt.isEmpty() || !encoder.matches(password, opt.get().getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        UserEntity user = opt.get();
        return authResponse(user, jwtService.issueTokens(user.getId(), user.getUsername()));
    }

    public Map<String, Object> refresh(String refreshToken) {
        JwtService.Claims claims = jwtService.verifyRefreshToken(refreshToken);
        UserEntity user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "用户不存在"));
        return authResponse(user, jwtService.issueTokens(user.getId(), user.getUsername()));
    }

    public Map<String, Object> register(String username, String password, String name,
                                        String school, String major, String grade) {
        username = InputSanitizer.clean(username, 50);
        name = InputSanitizer.clean(name, 50);
        school = InputSanitizer.clean(school, 100);
        major = InputSanitizer.clean(major, 50);
        grade = InputSanitizer.clean(grade, 20);
        if (userRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USERNAME_EXISTS", "用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setName(name != null && !name.isBlank() ? name : username);
        user.setSchool(school != null ? school : "");
        user.setMajor(major != null ? major : "");
        user.setGrade(grade != null ? grade : "");
        userRepository.save(user);
        userInfoCache.remove(user.getId());

        return authResponse(user, jwtService.issueTokens(user.getId(), user.getUsername()));
    }

    private Map<String, Object> authResponse(UserEntity user, JwtService.TokenPair tokens) {
        return Map.of(
                "token", tokens.accessToken(),
                "refreshToken", tokens.refreshToken(),
                "expiresAt", tokens.expiresAt(),
                "name", user.getName(),
                "username", user.getUsername(),
                "school", user.getSchool() != null ? user.getSchool() : "",
                "major", user.getMajor() != null ? user.getMajor() : "",
                "grade", user.getGrade() != null ? user.getGrade() : ""
        );
    }

    public long getUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录");
        }
        return jwtService.verifyAccessToken(authHeader.substring(7)).userId();
    }

    public void logout(String authHeader) {
        // JWT access tokens are stateless. The client discards its tokens on logout.
    }

    public Map<String, Object> getUserInfo(long userId) {
        long now = System.currentTimeMillis();
        CachedUserInfo cached = userInfoCache.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            return new java.util.HashMap<>(cached.info());
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "用户不存在"));
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", user.getName());
        info.put("username", user.getUsername());
        info.put("school", user.getSchool() != null ? user.getSchool() : "");
        info.put("major", user.getMajor() != null ? user.getMajor() : "");
        info.put("grade", user.getGrade() != null ? user.getGrade() : "");
        userInfoCache.put(userId, new CachedUserInfo(Map.copyOf(info), now + 300_000));
        return info;
    }

    private record CachedUserInfo(Map<String, Object> info, long expiresAt) {}
}
