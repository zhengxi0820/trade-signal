package com.xi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTML 页面响应禁用缓存（no-store）：
 * 避免浏览器复用旧版 index.html（曾出现旧版内联脚本被生产 CSP 拦截、
 * Vue 未挂载导致 {{ toast.text }} 等模板原样展示的问题）。
 * css/js 等静态资源不受影响（文件名已带 ?v= 版本号做缓存失效）。
 */
@Component
public class HtmlCacheHeaderFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"/".equals(path) && !path.endsWith(".html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        chain.doFilter(request, response);
    }
}
