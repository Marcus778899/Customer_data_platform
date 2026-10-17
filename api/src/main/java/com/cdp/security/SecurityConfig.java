package com.cdp.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 契約 §1.1 角色矩陣、§1.2 session cookie、§5 的 401／403 problem+json（含 request_id，RequestIdFilter 須排在
 * security filter 之前）。CSRF 關閉的前提：session 只能用於 GET §4 與 /api/v1/auth/*，寫入 API 只收 Bearer。
 *
 * <p>骨架刻意不加 @Bean：BE-04 合併前沿用 Boot 預設（全部需登入），其他單的 web 測試用 @WithMockUser。
 */
@Configuration
public class SecurityConfig {

    /** TODO(BE-04)：加 @Bean 並實作。 */
    public SecurityFilterChain apiFilterChain(HttpSecurity http, AuthProperties properties)
            throws Exception {
        throw new UnsupportedOperationException("TODO(BE-04)");
    }
}
