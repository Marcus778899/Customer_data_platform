package com.cdp.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cdp.common.web.ErrorCode;

/**
 * 契約 §1.1：讀 Authorization: Bearer，與 ingest／admin 金鑰常數時間比對，成功則放入 Role.INGEST／ADMIN；
 * 帶了但不符 → 401 UNAUTHENTICATED（不再看 session）。不加 @Component：只由 SecurityConfig 掛進 security filter chain，
 * 避免被 Boot 自動註冊成一般 servlet filter。
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final AuthProperties properties;

    public ApiKeyAuthenticationFilter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Role role = null;
        if (header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            String key = header.substring(PREFIX.length());
            // 兩把都比，不短路
            boolean ingest = SecretMatcher.matches(key, properties.ingestApiKey());
            boolean admin = SecretMatcher.matches(key, properties.adminApiKey());
            if (!key.isEmpty()) {
                role = admin ? Role.ADMIN : ingest ? Role.INGEST : null;
            }
        }
        if (role == null) {
            SecurityContextHolder.clearContext();
            SecurityProblemWriter.write(response, ErrorCode.UNAUTHENTICATED, "缺少有效的憑證");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                role.name().toLowerCase(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
