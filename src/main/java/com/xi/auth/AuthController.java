package com.xi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点：登录（密钥换 Cookie）、状态检查、登出。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录：同一 IP 连续失败 5 次锁 15 分钟（429）。
     * 成功返回 204 + HttpOnly 认证 Cookie，不落任何明文。
     */
    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginParam param,
                                      HttpServletRequest request, HttpServletResponse response) {
        String ip = authService.clientIp(request);
        if (authService.isLocked(ip)) {
            return ResponseEntity.status(429).build();
        }
        if (!authService.tryLogin(ip, param == null ? null : param.key())) {
            return ResponseEntity.status(401).build();
        }
        response.addCookie(authService.buildCookie(authService.issueToken()));
        return ResponseEntity.noContent().build();
    }

    /** 前端轮转用：已认证 204，未认证 401。 */
    @GetMapping("/check")
    public ResponseEntity<Void> check(HttpServletRequest request) {
        return authService.hasValidCookie(request)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(401).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        response.addCookie(authService.buildLogoutCookie());
        return ResponseEntity.noContent().build();
    }

    public record LoginParam(String key) {
    }
}
