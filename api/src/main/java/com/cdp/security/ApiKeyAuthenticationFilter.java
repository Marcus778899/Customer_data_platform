package com.cdp.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 契約 §1.1：讀 Authorization: Bearer，與 ingest／admin 金鑰常數時間比對，成功則放入 Role.INGEST／ADMIN；
 * 帶了但不符 → 401 UNAUTHENTICATED（不再看 session）。不加 @Component：只由 SecurityConfig 掛進 security filter chain，
 * 避免被 Boot 自動註冊成一般 servlet filter。
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final AuthProperties properties;

    public ApiKeyAuthenticationFilter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        throw new UnsupportedOperationException("TODO(BE-04)");
    }
}
