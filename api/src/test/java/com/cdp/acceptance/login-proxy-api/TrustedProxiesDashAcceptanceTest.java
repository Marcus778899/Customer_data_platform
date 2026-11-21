package com.cdp.acceptance.loginproxyapi;

import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.credentials;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * login-proxy-api 驗收（auth.md §3「取得來源」）：{@code cdp.security.trusted-proxies=-} 不信任任何代理，
 * 本機連線帶的 {@code X-Forwarded-For}（含鏈）一律不採信，所有請求算同一來源。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=3",
            "cdp.login.lockout=PT4S",
            "cdp.login.ip-max-failures=5",
            "cdp.security.trusted-proxies=-"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class TrustedProxiesDashAcceptanceTest {

    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String xff, String email, String password) throws Exception {
        return AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", Map.of("X-Forwarded-For", xff), credentials(email, password));
    }

    @Test
    void changing_forwarded_for_does_not_escape_the_email_lock() throws Exception {
        String victim = uniqueEmail();
        AuthHttp.createUserId(port, victim, "VIEWER");
        for (int i = 0; i < 3; i++) {
            assertThat(login("203.0.113." + (10 + i), victim, WRONG).status()).isEqualTo(401);
        }
        Resp r = login("203.0.113.99", victim, PASSWORD);
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
    }
}
