package com.cdp.security;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import com.cdp.common.web.ErrorCode;

/**
 * 契約 §1.1 角色矩陣、§1.2 session cookie、§5 的 401／403 problem+json（含 request_id，RequestIdFilter 須排在
 * security filter 之前）。CSRF 關閉的前提：session 只能用於 GET §4 與 /api/v1/auth/*，寫入 API 只收 Bearer。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http, AuthProperties properties)
            throws Exception {
        String ingest = Role.INGEST.name();
        String admin = Role.ADMIN.name();
        String support = Role.SUPPORT.name();
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .addFilterAfter(new ApiKeyAuthenticationFilter(properties), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/events/batch")
                        .hasRole(ingest)
                        .requestMatchers(HttpMethod.POST, "/api/v1/journeys").hasRole(admin)
                        .requestMatchers(HttpMethod.GET, "/api/v1/enrollments", "/api/v1/enrollments/**")
                        .hasAnyRole(admin, support)
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        // 其餘：GET 需認證；其他方法限 ADMIN（session 只能 GET）
                        .requestMatchers(HttpMethod.GET).authenticated()
                        .anyRequest().hasRole(admin))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                SecurityProblemWriter.write(res, ErrorCode.UNAUTHENTICATED, "缺少有效的憑證"))
                        .accessDeniedHandler((req, res, ex) ->
                                SecurityProblemWriter.write(res, ErrorCode.FORBIDDEN, "此憑證無權使用這個 API")));
        return http.build();
    }
}
