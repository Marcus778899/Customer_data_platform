package com.cdp.security;

import java.util.Set;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;

import com.cdp.common.web.ErrorCode;
import com.cdp.user.UserService;

/**
 * auth.md §1～§3：Bearer 金鑰與 session 兩種憑證、§2 角色矩陣（ADMIN ⊇ EDITOR ⊇ VIEWER）、§1.1 CSRF、
 * 401／403 problem+json（含 request_id，RequestIdFilter 須排在 security filter 之前）。
 *
 * <p>順序：認證（Bearer 或 session）→ CSRF（只對 session 的非 GET 請求；Bearer、login、logout 免）→ 角色 → 輸入驗證。
 */
@Configuration
public class SecurityConfig {

    private static final String V1 = "/api/v1";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** 宣告空的 UserDetailsService，避免 Spring Boot 自動產生預設帳號與密碼（認證只走 Bearer 與 session）。 */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name())
                .implies(Role.EDITOR.name())
                .role(Role.EDITOR.name())
                .implies(Role.VIEWER.name())
                .build();
    }

    /** XSRF-TOKEN 的 Secure 屬性與 session cookie 一致（server.servlet.session.cookie.secure，未設 = true）。 */
    @Bean
    public SessionCsrfTokenRepository csrfTokenRepository(ServerProperties serverProperties) {
        Boolean secure = serverProperties.getServlet().getSession().getCookie().getSecure();
        return new SessionCsrfTokenRepository(secure == null || secure);
    }

    @Bean
    public SecurityFilterChain apiFilterChain(
            HttpSecurity http,
            AuthProperties properties,
            UserService users,
            RoleHierarchy hierarchy,
            SessionCsrfTokenRepository csrfRepository)
            throws Exception {
        AccessRule ingest = AccessRule.role(hierarchy, Role.INGEST);
        AccessRule viewer = AccessRule.role(hierarchy, Role.VIEWER);
        AccessRule admin = AccessRule.role(hierarchy, Role.ADMIN);
        AccessRule adminKey = AccessRule.role(hierarchy, Role.ADMIN, Credential.BEARER);
        AccessRule anySession = AccessRule.role(hierarchy, Role.VIEWER, Credential.SESSION);
        AccessRule editorSession = AccessRule.role(hierarchy, Role.EDITOR, Credential.SESSION);
        AccessRule adminSession = AccessRule.role(hierarchy, Role.ADMIN, Credential.SESSION);

        http.csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfRepository)
                        .requireCsrfProtectionMatcher(SecurityConfig::sessionWriteRequest))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .addFilterAfter(new ApiKeyAuthenticationFilter(properties), SecurityContextHolderFilter.class)
                .addFilterAfter(new SessionAuthenticationFilter(users), ApiKeyAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, V1 + "/auth/login", V1 + "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, V1 + "/auth/me").access(anySession)
                        .requestMatchers(HttpMethod.POST, V1 + "/auth/password").access(anySession)
                        .requestMatchers(HttpMethod.POST, V1 + "/events", V1 + "/events/batch").access(ingest)
                        // PUT profiles：INGEST 與管理金鑰；ADMIN session 不可
                        .requestMatchers(HttpMethod.PUT, V1 + "/profiles/*")
                        .access(AuthorizationManagers.anyOf(ingest, adminKey))
                        .requestMatchers(HttpMethod.GET,
                                V1 + "/enrollments", V1 + "/enrollments/*", V1 + "/enrollments/*/deliveries",
                                V1 + "/profiles", V1 + "/profiles/**",
                                V1 + "/journeys", V1 + "/journeys/**")
                        .access(viewer)
                        // POST /journeys：只有管理金鑰（D-056 發佈須經審核；D-061）
                        .requestMatchers(HttpMethod.POST, V1 + "/journeys").access(adminKey)
                        // 草稿寫入、validate、送審、停止、撤回：EDITOR 以上的 session
                        .requestMatchers(HttpMethod.POST,
                                V1 + "/journeys/drafts", V1 + "/journeys/*/draft/validate",
                                V1 + "/journeys/*/draft/submit", V1 + "/journeys/*/stop",
                                V1 + "/journeys/*/reviews/*/withdraw")
                        .access(editorSession)
                        .requestMatchers(HttpMethod.PUT, V1 + "/journeys/*/draft").access(editorSession)
                        .requestMatchers(HttpMethod.DELETE, V1 + "/journeys/*/draft").access(editorSession)
                        // 核准、退回：只有 ADMIN 的 session（不收任何 Bearer）
                        .requestMatchers(HttpMethod.POST,
                                V1 + "/journeys/*/reviews/*/approve", V1 + "/journeys/*/reviews/*/reject")
                        .access(adminSession)
                        .requestMatchers(V1 + "/users", V1 + "/users/**").access(admin)
                        // 預設拒絕：契約未列的路徑只有 ADMIN 放行（落到 MVC 回 404／405）；其他角色 403、未認證 401
                        .anyRequest().access(admin))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                SecurityProblemWriter.write(res, ErrorCode.UNAUTHENTICATED, "缺少有效的憑證"))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                SecurityProblemWriter.write(
                                        res, ErrorCode.CSRF_INVALID, "CSRF 權杖缺少或不符，請重新呼叫 /api/v1/auth/me 取得新值。");
                            } else {
                                SecurityProblemWriter.write(res, ErrorCode.FORBIDDEN, "此憑證無權使用這個 API");
                            }
                        }));
        return http.build();
    }

    /** §1.1：只有 session 認證的非 GET 請求要檢查 CSRF；Bearer 與 login、logout 不檢查。 */
    private static boolean sessionWriteRequest(jakarta.servlet.http.HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri.equals(V1 + "/auth/login") || uri.equals(V1 + "/auth/logout")) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getAuthorities().stream().anyMatch(a -> Credential.SESSION.authority().equals(a.getAuthority()));
    }
}
