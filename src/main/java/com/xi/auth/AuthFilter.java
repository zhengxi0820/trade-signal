package com.xi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证过滤器：/kdj/** 与 /watchlist 必须带有效认证 Cookie，否则 401。
 * 静态页面（index.html 等）与 /auth/** 不拦——页面壳无数据，密钥弹窗就在里面。
 * 用户 token 在签名/过期校验后还做服务端吊销检查（禁用/改密即失效，见 UserService.isTokenActive）；
 * 密钥 token（subject="key"）纯无状态不查库。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final UserService userService;

    public AuthFilter(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/kdj/") && !uri.startsWith("/watchlist");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isAuthenticated(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 签名/过期校验 + 用户 token 吊销检查（key token 免查库）。 */
    public boolean isAuthenticated(HttpServletRequest request) {
        AuthService.ParsedToken token = authService.parse(request);
        return token != null && ("key".equals(token.subject())
                || userService.isTokenActive(token.subject(), token.issuedAt()));
    }
}
