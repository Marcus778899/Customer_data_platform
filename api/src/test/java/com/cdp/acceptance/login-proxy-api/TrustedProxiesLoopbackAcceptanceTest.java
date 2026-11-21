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
 * login-proxy-api 驗收（auth.md §3「取得來源」「計數鍵」）：信任 {@code 127.0.0.1,::1}（測試連線來自本機）時，
 * {@code X-Forwarded-For} 由右往左取第一個非信任位址；IPv6 以 /64 計數；IPv4-mapped 視為 IPv4。
 * 每個測試用各自的來源位址，避免計數互相影響。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=3",
            "cdp.login.lockout=PT30S",
            "cdp.login.ip-max-failures=5",
            "cdp.security.trusted-proxies=127.0.0.1,::1"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class TrustedProxiesLoopbackAcceptanceTest {

    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String xff, String email, String password) throws Exception {
        return AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", Map.of("X-Forwarded-For", xff), credentials(email, password));
    }

    private void assertWrong(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(401);
    }

    private void assertLocked(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
    }

    private String victim() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        return email;
    }

    @Test
    void trusted_proxy_forwarded_for_selects_the_source() throws Exception {
        String v = victim();
        for (int i = 0; i < 3; i++) assertWrong(login("203.0.113.10", v, WRONG));
        assertLocked(login("203.0.113.10", v, PASSWORD));
        assertThat(login("203.0.113.11", v, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void takes_first_untrusted_address_from_the_right() throws Exception {
        String v = victim();
        // 偽造的左段每次不同，最右邊的非信任位址相同 → 同一來源
        assertWrong(login("9.9.9.1, 203.0.113.20", v, WRONG));
        assertWrong(login("9.9.9.2, 203.0.113.20", v, WRONG));
        assertWrong(login("9.9.9.3, 203.0.113.20", v, WRONG));
        assertLocked(login("9.9.9.4, 203.0.113.20", v, PASSWORD));
        // 最右邊的位址不同 → 另一個來源
        assertThat(login("9.9.9.1, 203.0.113.21", v, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void trusted_addresses_at_the_right_end_are_skipped() throws Exception {
        String v = victim();
        for (int i = 0; i < 3; i++) assertWrong(login("203.0.113.30, 127.0.0.1", v, WRONG));
        // 右端的 127.0.0.1 在信任清單內被跳過，來源是 203.0.113.30
        assertLocked(login("203.0.113.30", v, PASSWORD));
        assertLocked(login("8.8.8.8, 203.0.113.30, ::1", v, PASSWORD));
    }

    @Test
    void ipv6_in_the_same_slash_64_share_the_source_count() throws Exception {
        String v = victim();
        assertWrong(login("2001:db8:1:2::1", v, WRONG));
        assertWrong(login("2001:db8:1:2:ffff::9", v, WRONG));
        assertWrong(login("2001:DB8:1:2:0:0:0:3", v, WRONG));
        assertLocked(login("2001:db8:1:2:aaaa:bbbb:cccc:dddd", v, PASSWORD));
    }

    @Test
    void ipv6_in_different_slash_64_do_not_share_the_count() throws Exception {
        String v = victim();
        for (int i = 0; i < 3; i++) assertWrong(login("2001:db8:2:2::1", v, WRONG));
        assertLocked(login("2001:db8:2:2::1", v, PASSWORD));
        assertThat(login("2001:db8:2:3::1", v, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void source_lock_counts_a_whole_slash_64_together() throws Exception {
        String real = victim();
        for (int i = 0; i < 5; i++) assertWrong(login("2001:db8:3:3::" + (i + 1), uniqueEmail(), WRONG));
        assertLocked(login("2001:db8:3:3:1:2:3:4", real, PASSWORD));
        assertThat(login("2001:db8:3:4::1", real, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void ipv4_mapped_ipv6_is_the_same_source_as_ipv4() throws Exception {
        String v = victim();
        assertWrong(login("203.0.113.40", v, WRONG));
        assertWrong(login("::ffff:203.0.113.40", v, WRONG));
        assertWrong(login("::FFFF:203.0.113.40", v, WRONG));
        assertLocked(login("203.0.113.40", v, PASSWORD));
        assertLocked(login("::ffff:cb00:7128", v, PASSWORD)); // 203.0.113.40 的十六進位寫法
    }

    @Test
    void ipv4_addresses_in_the_same_slash_24_are_not_merged() throws Exception {
        String v = victim();
        for (int i = 0; i < 3; i++) assertWrong(login("203.0.113.50", v, WRONG));
        assertThat(login("203.0.113.51", v, PASSWORD).status()).isEqualTo(204);
    }
}
