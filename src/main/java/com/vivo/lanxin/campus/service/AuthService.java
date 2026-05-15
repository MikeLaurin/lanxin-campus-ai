package com.vivo.lanxin.campus.service;

import com.vivo.lanxin.campus.model.UserEntity;
import com.vivo.lanxin.campus.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        Optional<UserEntity> opt = userRepository.findByUsername(username);
        if (opt.isEmpty() || !encoder.matches(password, opt.get().getPassword())) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        UserEntity user = opt.get();
        String token = UUID.randomUUID().toString();
        tokens.put(token, user.getId());
        return Map.of(
                "token", token,
                "name", user.getName(),
                "username", user.getUsername(),
                "school", user.getSchool() != null ? user.getSchool() : "",
                "major", user.getMajor() != null ? user.getMajor() : "",
                "grade", user.getGrade() != null ? user.getGrade() : ""
        );
    }

    public Map<String, Object> register(String username, String password, String name,
                                         String school, String major, String grade) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setName(name != null && !name.isBlank() ? name : username);
        user.setSchool(school != null ? school : "");
        user.setMajor(major != null ? major : "");
        user.setGrade(grade != null ? grade : "");
        userRepository.save(user);

        String token = UUID.randomUUID().toString();
        tokens.put(token, user.getId());
        return Map.of(
                "token", token,
                "name", user.getName(),
                "username", user.getUsername(),
                "school", user.getSchool(),
                "major", user.getMajor(),
                "grade", user.getGrade()
        );
    }

    public long getUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("未登录");
        }
        String token = authHeader.substring(7);
        Long userId = tokens.get(token);
        if (userId == null) {
            throw new IllegalArgumentException("登录已过期，请重新登录");
        }
        return userId;
    }

    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokens.remove(authHeader.substring(7));
        }
    }

    public Map<String, Object> getUserInfo(long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("name", user.getName());
        info.put("username", user.getUsername());
        info.put("school", user.getSchool() != null ? user.getSchool() : "");
        info.put("major", user.getMajor() != null ? user.getMajor() : "");
        info.put("grade", user.getGrade() != null ? user.getGrade() : "");
        return info;
    }
}
