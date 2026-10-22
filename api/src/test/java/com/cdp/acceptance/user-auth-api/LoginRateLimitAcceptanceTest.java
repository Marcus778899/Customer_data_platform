package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.credentials;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/**
 * user-auth-api 驗收：auth.md §3 登入限速表格逐列。門檻 3 次、鎖定 4 秒。
 *
 * <p>假設：CDP_LOGIN_MAX_FAILURES／CDP_LOGIN_LOCKOUT 對應屬性 {@code cdp.login.max-failures}／{@code cdp.login.lockout}
 * （同 {@code CDP_LOOP_MAX_DRAIN → cdp.loop.max-drain} 的命名規則）；同時也設 {@code cdp.security.login-*} 以防另取名，多設的屬性無害。
 * 「鎖定中不跑 BCrypt」屬耗時行為，不在此斷言（由單元測試／verifier 驗證）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.login.max-failures=3",
            "cdp.login.lockout=PT4S",
            "cdp.security.login-max-failures=3",
            "cdp.security.login-lockout=PT4S"
        })
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LoginRateLimitAcceptanceTest {

    private static final String WRONG = "definitely-wrong-password";

    @LocalServerPort int port;

    private Resp login(String email, String password) throws Exception {
        return AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, credentials(email, password));
    }

    private void assertWrong(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    private void assertLocked(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(429);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(r.json().path("request_id").asString()).isNotBlank();
        assertThat(r.setCookie("CDP_SESSION")).isNull();
    }

    private void lock(String email) throws Exception {
        for (int i = 0; i < 3; i++) assertWrong(login(email, WRONG));
    }

    private int retryAfter(Resp r) {
        return Integer.parseInt(r.headers().firstValue("Retry-After").orElseThrow());
    }

    @Test
    void locks_after_max_failures_even_for_correct_password() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        lock(email); // 第 3 次仍回 401（達門檻的那次是密碼錯），之後才鎖
        assertLocked(login(email, PASSWORD));
        assertLocked(login(email, WRONG));
    }

    @Test
    void retry_after_is_integer_seconds_rounded_up() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        lock(email);
        // 剛鎖定：剩餘 (3, 4] 秒 → 4（無條件捨去會得到 3）
        Resp first = login(email, PASSWORD);
        assertLocked(first);
        assertThat(retryAfter(first)).isEqualTo(4);
        Thread.sleep(1500);
        // 剩餘約 2.0–2.5 秒 → 3
        Resp later = login(email, PASSWORD);
        assertLocked(later);
        assertThat(retryAfter(later)).isEqualTo(3);
    }

    @Test
    void attempts_while_locked_do_not_extend_the_lock() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        lock(email);
        Thread.sleep(2000);
        assertLocked(login(email, WRONG)); // 鎖定中的嘗試不計數、不延長
        Thread.sleep(2600); // 距最後一次失敗約 4.6 秒
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void unknown_email_counts_and_response_matches_existing_accounts() throws Exception {
        String ghost = uniqueEmail();
        String real = uniqueEmail();
        AuthHttp.createUserId(port, real, "VIEWER");
        Resp ghostWrong = login(ghost, WRONG);
        Resp realWrong = login(real, WRONG);
        assertThat(ghostWrong.status()).isEqualTo(realWrong.status());
        assertThat(ghostWrong.json().path("detail")).isEqualTo(realWrong.json().path("detail"));
        for (int i = 0; i < 2; i++) {
            assertWrong(login(ghost, WRONG));
            assertWrong(login(real, WRONG));
        }
        Resp ghostLocked = login(ghost, PASSWORD);
        Resp realLocked = login(real, PASSWORD);
        assertLocked(ghostLocked);
        assertLocked(realLocked);
        assertThat(retryAfter(ghostLocked)).isBetween(1, 4);
    }

    @Test
    void counting_is_per_lowercased_email() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        assertWrong(login(email.toUpperCase(), WRONG));
        assertWrong(login(email.toLowerCase(), WRONG));
        assertWrong(login(email, WRONG));
        assertLocked(login(email.toLowerCase(), PASSWORD));
        assertLocked(login(email.toUpperCase(), PASSWORD));
    }

    @Test
    void other_emails_are_not_affected() throws Exception {
        String a = uniqueEmail();
        String b = uniqueEmail();
        AuthHttp.createUserId(port, a, "VIEWER");
        AuthHttp.createUserId(port, b, "VIEWER");
        lock(a);
        assertLocked(login(a, PASSWORD));
        assertThat(login(b, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void success_resets_the_counter() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        assertWrong(login(email, WRONG));
        assertWrong(login(email, WRONG));
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
        // 若沒歸零，下面第 2 次就會鎖定
        assertWrong(login(email, WRONG));
        assertWrong(login(email, WRONG));
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void validation_errors_do_not_count() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        for (int i = 0; i < 5; i++) {
            Resp r = AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, "{\"email\":\"" + email + "\"}");
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void counter_restarts_when_last_failure_is_older_than_lockout() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        assertWrong(login(email, WRONG));
        assertWrong(login(email, WRONG));
        Thread.sleep(4500); // 距上次失敗超過 CDP_LOGIN_LOCKOUT → 先歸零再計
        assertWrong(login(email, WRONG)); // 1
        assertWrong(login(email, WRONG)); // 2（沒歸零的話這裡之後就是 429）
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void lock_expires_after_the_lockout_period() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        lock(email);
        assertLocked(login(email, PASSWORD));
        Thread.sleep(4500);
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
        // 解鎖且成功後計數歸零，可再錯兩次不鎖
        assertWrong(login(email, WRONG));
        assertWrong(login(email, WRONG));
    }

    @Test
    void password_endpoint_does_not_count() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        String cookie = AuthHttp.login(port, email, PASSWORD);
        for (int i = 0; i < 5; i++) {
            Resp r = AuthHttp.session(
                    port,
                    "POST",
                    "/api/v1/auth/password",
                    cookie,
                    "{\"current_password\":\"" + WRONG + "\",\"new_password\":\"another-long-password-9\"}");
            assertWrong(r);
        }
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }
}
