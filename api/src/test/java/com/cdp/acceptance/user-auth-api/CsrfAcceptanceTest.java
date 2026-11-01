package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

/** user-auth-api 驗收：auth.md §1.1 CSRF（session 的非 GET 請求）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class CsrfAcceptanceTest {

    @LocalServerPort int port;

    private String newUserBody() {
        return AuthHttp.userBody(AuthHttp.uniqueEmail(), "csrf target", "VIEWER", AuthHttp.PASSWORD);
    }

    private Resp post(String cookie, Map<String, String> extra, String body) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cookie", cookie);
        h.putAll(extra);
        return AuthHttp.sendRaw(port, "POST", "/api/v1/users", h, body);
    }

    private void assertCsrfInvalid(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(403);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("CSRF_INVALID");
        assertThat(r.json().path("request_id").asString()).isNotBlank();
    }

    @Test
    void session_write_without_header_is_csrf_invalid() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        assertCsrfInvalid(post(cookie, Map.of(), newUserBody()));
    }

    @Test
    void session_write_with_wrong_header_is_csrf_invalid() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        assertCsrfInvalid(post(cookie, Map.of("X-XSRF-TOKEN", "not-the-token"), newUserBody()));
        assertCsrfInvalid(post(cookie, Map.of("X-XSRF-TOKEN", ""), newUserBody()));
    }

    @Test
    void session_write_with_header_but_no_xsrf_cookie_is_csrf_invalid() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        String sessionOnly = "CDP_SESSION=" + AuthHttp.cookieValue(cookie, "CDP_SESSION");
        assertCsrfInvalid(post(sessionOnly, Map.of("X-XSRF-TOKEN", AuthHttp.xsrf(cookie)), newUserBody()));
    }

    @Test
    void session_write_with_matching_header_succeeds_for_all_write_methods() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        Map<String, String> h = Map.of("Cookie", cookie, "X-XSRF-TOKEN", AuthHttp.xsrf(cookie));
        Resp created = AuthHttp.sendRaw(port, "POST", "/api/v1/users", h, newUserBody());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.json().path("user_id").asString();
        Resp patched = AuthHttp.sendRaw(port, "PATCH", "/api/v1/users/" + id, h, "{\"display_name\":\"x\"}");
        assertThat(patched.status()).as(patched.body()).isEqualTo(200);
        Resp noHeaderPatch = AuthHttp.sendRaw(
                port, "PATCH", "/api/v1/users/" + id, Map.of("Cookie", cookie), "{\"display_name\":\"y\"}");
        assertCsrfInvalid(noHeaderPatch);
    }

    @Test
    void get_needs_no_csrf_header() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        Resp r = AuthHttp.sendRaw(port, "GET", "/api/v1/users", Map.of("Cookie", cookie), null);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void me_gives_a_fresh_token_so_retry_succeeds() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        // 模擬前端遇 CSRF_INVALID：丟掉舊值、呼叫 /auth/me 取新值後重試
        String stale = "CDP_SESSION=" + AuthHttp.cookieValue(cookie, "CDP_SESSION") + "; XSRF-TOKEN=stale";
        assertCsrfInvalid(post(stale, Map.of("X-XSRF-TOKEN", "stale"), newUserBody()));
        Resp me = AuthHttp.sendRaw(port, "GET", "/api/v1/auth/me", Map.of("Cookie", stale), null);
        assertThat(me.status()).isEqualTo(200);
        String refreshed = AuthHttp.merge(stale, me);
        assertThat(AuthHttp.xsrf(refreshed)).isNotEqualTo("stale");
        Resp retry = AuthHttp.session(port, "POST", "/api/v1/users", refreshed, newUserBody());
        assertThat(retry.status()).as(retry.body()).isEqualTo(201);
    }

    @Test
    void bearer_requests_skip_csrf_even_when_a_session_cookie_is_present() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        Resp r = AuthHttp.sendRaw(
                port, "POST", "/api/v1/users", Map.of("Authorization", ADMIN_KEY, "Cookie", cookie), newUserBody());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
    }

    @Test
    void login_and_logout_are_exempt() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        // 帶有效 session 但沒有 CSRF 標頭
        Resp out = AuthHttp.sendRaw(port, "POST", "/api/v1/auth/logout", Map.of("Cookie", cookie), null);
        assertThat(out.status()).isEqualTo(204);
        Resp login = AuthHttp.sendRaw(
                port,
                "POST",
                "/api/v1/auth/login",
                Map.of("Cookie", cookie),
                AuthHttp.credentials("nobody@example.com", "whatever-password"));
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void token_from_an_earlier_login_is_not_accepted_after_relogin() throws Exception {
        String email = AuthHttp.uniqueEmail();
        AuthHttp.createUserId(port, email, "ADMIN");
        String first = AuthHttp.login(port, email, AuthHttp.PASSWORD);
        String second = AuthHttp.login(port, email, AuthHttp.PASSWORD);
        // 新 session 搭舊 token（登入使舊值失效）
        String mixed = "CDP_SESSION=" + AuthHttp.cookieValue(second, "CDP_SESSION")
                + "; XSRF-TOKEN=" + AuthHttp.xsrf(first);
        assertCsrfInvalid(post(mixed, Map.of("X-XSRF-TOKEN", AuthHttp.xsrf(first)), newUserBody()));
    }
}
