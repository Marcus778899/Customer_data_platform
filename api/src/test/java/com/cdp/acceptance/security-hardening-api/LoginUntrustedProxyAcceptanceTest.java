package com.cdp.acceptance.securityhardeningapi;

import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.credentials;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * security-hardening-api 驗收（auth.md §3 v0.3.0「來源 IP」）：直接連線者（不在信任代理範圍）帶的
 * {@code X-Forwarded-For} 不採信，不能靠偽造標頭繞過鎖定或換來源。以把信任範圍設成一個不含本機的位址模擬
 * （CDP_TRUSTED_PROXIES 最終落在 {@code server.tomcat.remoteip.internal-proxies}）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=3",
            "cdp.login.lockout=PT4S",
            "cdp.login.ip-max-failures=5",
            "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1"
        })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Import(SecurityTestSupport.class)
class LoginUntrustedProxyAcceptanceTest {

    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String xff, String email, String password) throws Exception {
        return AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", Map.of("X-Forwarded-For", xff), credentials(email, password));
    }

    @Test
    void forged_forwarded_for_does_not_escape_the_email_lock() throws Exception {
        String victim = uniqueEmail();
        AuthHttp.createUserId(port, victim, "VIEWER");
        for (int i = 0; i < 3; i++) {
            assertThat(login("203.0.113." + (100 + i), victim, WRONG).status()).isEqualTo(401);
        }
        // 換了偽造的來源，真實連線端位址沒變 → 仍被鎖
        Resp r = login("203.0.113.200", victim, PASSWORD);
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
    }

    @Test
    void forged_forwarded_for_does_not_escape_the_source_lock() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("198.51.100." + (100 + i), uniqueEmail(), WRONG).status())
                    .isEqualTo(401);
        }
        Resp r = login("198.51.100.250", uniqueEmail(), WRONG);
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
    }
}
