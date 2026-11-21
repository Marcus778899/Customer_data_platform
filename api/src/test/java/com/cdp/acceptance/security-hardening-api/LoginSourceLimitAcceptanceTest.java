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
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * security-hardening-api 驗收（#153，auth.md §3 v0.3.0）：帳號鎖定以 email＋來源 IP 為單位、來源 IP 總量鎖定。
 * 門檻：email＋IP 3 次、來源總量 5 次、鎖定 4 秒。測試連線來自本機，以 {@code cdp.security.trusted-proxies=127.0.0.1,::1}
 * 明確信任 loopback（D-091 起無預設信任），再以 {@code X-Forwarded-For} 模擬不同來源。假設屬性名 {@code cdp.login.ip-max-failures}（CDP_LOGIN_IP_MAX_FAILURES）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=3",
            "cdp.login.lockout=PT4S",
            "cdp.login.ip-max-failures=5",
            "cdp.security.trusted-proxies=127.0.0.1,::1"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LoginSourceLimitAcceptanceTest {

    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String ip, String email, String password) throws Exception {
        return AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", Map.of("X-Forwarded-For", ip), credentials(email, password));
    }

    private void assertWrong(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    private void assertLocked(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(r.setCookie("CDP_SESSION")).isNull();
        assertThat(Integer.parseInt(r.headers().firstValue("Retry-After").orElseThrow()))
                .isBetween(1, 4);
    }

    @Test
    void attacker_cannot_lock_the_victim_out_from_another_source() throws Exception {
        String victim = uniqueEmail();
        AuthHttp.createUserId(port, victim, "VIEWER");
        for (int i = 0; i < 3; i++) assertWrong(login("203.0.113.10", victim, WRONG));
        // 攻擊者的來源被鎖（連正確密碼也不行）
        assertLocked(login("203.0.113.10", victim, PASSWORD));
        // 受害者從別的來源用正確密碼照常登入
        assertThat(login("203.0.113.11", victim, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void email_lock_counts_are_not_summed_across_sources() throws Exception {
        String victim = uniqueEmail();
        AuthHttp.createUserId(port, victim, "VIEWER");
        for (int i = 0; i < 2; i++) assertWrong(login("203.0.113.20", victim, WRONG));
        for (int i = 0; i < 2; i++) assertWrong(login("203.0.113.21", victim, WRONG));
        // 兩個來源各 2 次（合計 4 > 門檻 3），但各自未達門檻：第 3 次仍是 401，不是 429
        assertWrong(login("203.0.113.20", victim, WRONG));
        assertThat(login("203.0.113.22", victim, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void source_is_locked_after_ip_max_failures_across_emails() throws Exception {
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");
        String ip = "198.51.100.30";
        for (int i = 0; i < 5; i++) assertWrong(login(ip, uniqueEmail(), WRONG)); // 每個 email 只錯 1 次
        // 來源總量達 5 → 該來源所有登入 429，含存在的帳號與正確密碼，也含全新的 email
        assertLocked(login(ip, real, PASSWORD));
        assertLocked(login(ip, uniqueEmail(), WRONG));
        // 其他來源不受影響
        assertThat(login("198.51.100.31", real, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void source_lock_expires_after_the_lockout_period() throws Exception {
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");
        String ip = "198.51.100.40";
        for (int i = 0; i < 5; i++) assertWrong(login(ip, uniqueEmail(), WRONG));
        assertLocked(login(ip, real, PASSWORD));
        Thread.sleep(4600);
        assertThat(login(ip, real, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void attempts_while_source_is_locked_do_not_extend_the_lock() throws Exception {
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");
        String ip = "198.51.100.50";
        for (int i = 0; i < 5; i++) assertWrong(login(ip, uniqueEmail(), WRONG));
        Thread.sleep(2000);
        assertLocked(login(ip, real, WRONG)); // 鎖定中不計數、不延長
        Thread.sleep(2700); // 距最後一次失敗約 4.7 秒
        assertThat(login(ip, real, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void validation_errors_and_successes_do_not_count_toward_source_limit() throws Exception {
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");
        String ip = "198.51.100.60";
        for (int i = 0; i < 8; i++) {
            Resp r = AuthHttp.sendRaw(
                    port,
                    "POST",
                    "/api/v1/auth/login",
                    Map.of("X-Forwarded-For", ip),
                    "{\"email\":\"" + real + "\"}");
            assertThat(r.status()).isEqualTo(400);
        }
        for (int i = 0; i < 8; i++) assertThat(login(ip, real, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void locked_source_response_is_problem_json_with_request_id() throws Exception {
        String ip = "198.51.100.70";
        for (int i = 0; i < 5; i++) assertWrong(login(ip, uniqueEmail(), WRONG));
        Resp r = login(ip, uniqueEmail(), WRONG);
        assertLocked(r);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.json().path("request_id").asString()).isNotBlank();
    }
}
