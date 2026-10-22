package com.cdp.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cdp.user.AppUser;
import com.cdp.user.UserService;

/**
 * auth.md §1：沒有 Authorization 標頭時，由 cookie 對應的 session 取出 user_id，每個請求都重新載入使用者
 * （停用、刪除 → 視為未認證並丟棄 session；角色變更下一個請求即生效）。帶了 Authorization 就完全不看 session
 * （{@link ApiKeyAuthenticationFilter} 負責）。不加 @Component：只由 SecurityConfig 掛進 security filter chain。
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    /** session 內只存使用者 ID。 */
    public static final String USER_ID_ATTRIBUTE = "user_id";

    private final UserService users;

    public SessionAuthenticationFilter(UserService users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("Authorization") == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                authenticate(session);
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpSession session) {
        Object id = session.getAttribute(USER_ID_ATTRIBUTE);
        if (!(id instanceof String userId)) {
            return;
        }
        AppUser user = users.findActiveById(userId).orElse(null);
        if (user == null) {
            session.invalidate();
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                SessionUser.of(user),
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_" + user.role().name()),
                        new SimpleGrantedAuthority(Credential.SESSION.authority()))));
        SecurityContextHolder.setContext(context);
    }
}
