package com.adaptivelearning.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 校验 Java 后端入站内部端点（{@code /internal/**}）的 X-Internal-Token 头，
 * 与配置的 {@code app.ai-service.internal-token} 一致才放行，否则返回 401。
 * 内部端点是 Python AI 服务拉取配置/提示词等数据的信任通道，不参与 JWT 认证。
 */
@Component
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(@Value("${app.ai-service.internal-token:}") String internalToken,
                               ObjectMapper objectMapper) {
        this.expected = internalToken == null ? null : internalToken.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("X-Internal-Token");
        if (expected != null && header != null && MessageDigest.isEqual(
                expected, header.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        if (expected == null || expected.length == 0) {
            log.error("internal_token_not_configured uri={}", request.getRequestURI());
        } else {
            log.warn("internal_token_rejected uri={}", request.getRequestURI());
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "success", false,
                "error", Map.of("code", "AUTH_UNAUTHENTICATED", "message", "请先登录"),
                "requestId", String.valueOf(request.getAttribute("requestId"))));
    }
}
