package com.cdp.acceptance.loginproxyapi;

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
            "cdp.login.lockout=PT5S",
            "cdp.login.ip-max-failures=5",
            "cdp.security.trusted-proxies=-"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class TrustedProxiesDashChainAcceptanceTest {

    // lockout 與另一個類別不同，讓兩者各用獨立的 Spring context（不共用來源計數）
    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String xff, String email, String password) throws Exception {
        return AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", Map.of("X-Forwarded-For", xff), credentials(email, password));
    }

    @Test
    void chained_forwarded_for_is_ignored_for_the_source_lock() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("198.51.100." + (10 + i) + ", 198.51.100.200", uniqueEmail(), WRONG)
                            .status())
                    .isEqualTo(401);
        }
        Resp r = login("2001:db8:9:9::1", uniqueEmail(), WRONG);
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
    }
}
