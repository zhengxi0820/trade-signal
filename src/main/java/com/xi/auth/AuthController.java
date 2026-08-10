package com.xi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证端点：注册（邀请码制）、登录（密钥或用户名+密码）、状态检查、登出。
 * 限流口径：同一 IP 连续失败 5 次锁 15 分钟（429）；注册另有同 IP 每日成功上限。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /**
     * 注册：邀请码 + 用户名 + 密码。成功自动登录（204 + 认证 Cookie）。
     * 400=参数/邀请码/重名（带 message）；403=注册未开放；429=限流。
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterParam param,
                                                        HttpServletRequest request, HttpServletResponse response) {
        String ip = authService.clientIp(request);
        if (authService.isLocked(ip)) {
            return ResponseEntity.status(429).build();
        }
        if (!userService.registerEnabled()) {
            return ResponseEntity.status(403).build();
        }
        if (!authService.canRegister(ip)) {
            return ResponseEntity.status(429).build();
        }
        String username = param == null ? null : param.username();
        String password = param == null ? null : param.password();
        String inviteCode = param == null ? null : param.inviteCode();
        if (!userService.isValidInviteCode(inviteCode)) {
            authService.noteAuthFail(ip, "inviteCode");
            return badRequest("邀请码无效");
        }
        String paramError = userService.validateParam(username, password);
        if (paramError != null) {
            authService.noteAuthFail(ip, "param");
            return badRequest(paramError);
        }
        if (!userService.register(ip, username, password, inviteCode)) {
            authService.noteAuthFail(ip, "duplicate");
            return badRequest("用户名已存在");
        }
        authService.noteRegisterSuccess(ip);
        response.addCookie(authService.buildCookie(authService.issueToken(username)));
        return ResponseEntity.noContent().build();
    }

    /**
     * 登录：body 带 key 走密钥登录（服务器脚本用）；带 username+password 走用户登录。
     * 成功返回 204 + HttpOnly 认证 Cookie，不落任何明文。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginParam param,
                                                     HttpServletRequest request, HttpServletResponse response) {
        String ip = authService.clientIp(request);
        if (authService.isLocked(ip)) {
            return ResponseEntity.status(429).build();
        }
        if (param != null && StringUtils.hasText(param.username())) {
            if (!userService.tryUserLogin(ip, param.username(), param.password())) {
                authService.noteAuthFail(ip, "login");
                return unauthorized("用户名或密码错误，还可尝试 " + authService.remainingAttempts(ip) + " 次");
            }
            response.addCookie(authService.buildCookie(authService.issueToken(param.username())));
            return ResponseEntity.noContent().build();
        }
        if (!authService.tryLogin(ip, param == null ? null : param.key())) {
            return unauthorized("密钥错误，还可尝试 " + authService.remainingAttempts(ip) + " 次");
        }
        response.addCookie(authService.buildCookie(authService.issueToken("key")));
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

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    private static ResponseEntity<Map<String, String>> unauthorized(String message) {
        return ResponseEntity.status(401).body(Map.of("message", message));
    }

    public record LoginParam(String key, String username, String password) {
    }

    public record RegisterParam(String username, String password, String inviteCode) {
    }
}
