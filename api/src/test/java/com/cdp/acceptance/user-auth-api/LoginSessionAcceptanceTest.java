package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.credentials;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

import tools.jackson.databind.JsonNode;

/** user-auth-api 驗收：auth.md §3 登入、登出、me、改密碼與 session 行為。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LoginSessionAcceptanceTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private Resp login(String email, String password) throws Exception {
        return AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, credentials(email, password));
    }

    private void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode n = r.json();
        assertThat(n.path("type").asString()).isEqualTo("about:blank");
        assertThat(n.path("status").asInt()).isEqualTo(status);
        assertThat(n.path("code").asString()).isEqualTo(code);
        assertThat(n.path("request_id").asString()).isNotBlank();
    }

    // ---- login ----

    @Test
    void login_sets_session_and_xsrf_cookies() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "EDITOR");
        Resp r = login(email, PASSWORD);
        assertThat(r.status()).isEqualTo(204);
        String sc = r.setCookie("CDP_SESSION");
        assertThat(sc).isNotNull();
        assertThat(sc).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Strict");
        assertThat(sc).contains("Path=/api").doesNotContain("Path=/api/");
        String x = r.setCookie("XSRF-TOKEN");
        assertThat(x).as("login must also set XSRF-TOKEN: %s", r.setCookies()).isNotNull();
        assertThat(x).contains("Path=/").containsIgnoringCase("SameSite=Strict");
        assertThat(x).doesNotContainIgnoringCase("HttpOnly");
    }

    @Test
    void me_always_sets_a_fresh_xsrf_cookie() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        Resp me = AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null);
        assertThat(me.status()).isEqualTo(200);
        String x = me.setCookie("XSRF-TOKEN");
        assertThat(x).as("me must set XSRF-TOKEN").isNotNull();
        assertThat(x).contains("Path=/").containsIgnoringCase("SameSite=Strict");
        assertThat(x).doesNotContainIgnoringCase("HttpOnly");
    }

    @Test
    void each_login_issues_a_new_session_id_and_xsrf_changes() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        String a = AuthHttp.login(port, email, PASSWORD);
        String b = AuthHttp.login(port, email, PASSWORD);
        assertThat(AuthHttp.cookieValue(b, "CDP_SESSION")).isNotEqualTo(AuthHttp.cookieValue(a, "CDP_SESSION"));
        assertThat(AuthHttp.xsrf(b)).isNotEqualTo(AuthHttp.xsrf(a));
        // 帶著舊 cookie 再登入也要換新 id（防 session fixation）
        Resp r = AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", java.util.Map.of("Cookie", a), credentials(email, PASSWORD));
        assertThat(r.status()).isEqualTo(204);
        assertThat(AuthHttp.cookieValue(AuthHttp.merge(null, r), "CDP_SESSION"))
                .isNotEqualTo(AuthHttp.cookieValue(a, "CDP_SESSION"));
    }

    @Test
    void email_is_case_insensitive_and_stored_lowercase() throws Exception {
        String email = "MiXeD" + System.nanoTime() + "@Example.COM";
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE user_id = ?", String.class, id))
                .isEqualTo(email.toLowerCase());
        assertThat(login(email.toUpperCase(), PASSWORD).status()).isEqualTo(204);
        assertThat(login(email.toLowerCase(), PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void password_is_stored_as_bcrypt_hash() throws Exception {
        String id = AuthHttp.createUserId(port, uniqueEmail(), "VIEWER");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE user_id = ?", String.class, id);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void bad_credentials_are_indistinguishable() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        String disabled = uniqueEmail();
        String disabledId = AuthHttp.createUserId(port, disabled, "VIEWER");
        assertThat(AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + disabledId, ADMIN_KEY, "{\"status\":\"DISABLED\"}")
                        .status())
                .isEqualTo(200);

        Resp wrongPass = login(email, "definitely-wrong-password");
        Resp unknown = login(uniqueEmail(), PASSWORD);
        Resp disabledResp = login(disabled, PASSWORD);
        for (Resp r : new Resp[] {wrongPass, unknown, disabledResp}) {
            assertProblem(r, 401, "INVALID_CREDENTIALS");
            assertThat(r.setCookie("CDP_SESSION")).isNull();
        }
        assertThat(unknown.json().path("detail")).isEqualTo(wrongPass.json().path("detail"));
        assertThat(disabledResp.json().path("detail")).isEqualTo(wrongPass.json().path("detail"));
    }

    @Test
    void login_validation_and_media_type() throws Exception {
        String email = uniqueEmail();
        assertProblem(
                AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, "{\"email\":\"" + email + "\"}"),
                400,
                "VALIDATION_FAILED");
        assertProblem(
                AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, "{\"password\":\"" + PASSWORD + "\"}"),
                400,
                "VALIDATION_FAILED");
        assertProblem(login("", ""), 400, "VALIDATION_FAILED");
        // v0.1.0 的 username 欄位不再被接受
        assertProblem(
                AuthHttp.sendRaw(
                        port,
                        "POST",
                        "/api/v1/auth/login",
                        null,
                        "{\"username\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"),
                400,
                "VALIDATION_FAILED");
        Resp text = AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", java.util.Map.of("Content-Type", "text/plain"), null);
        assertProblem(text, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void login_needs_no_csrf_header() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "VIEWER");
        // sendRaw 不帶 X-XSRF-TOKEN，也沒有 XSRF cookie
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    // ---- me ----

    @Test
    void me_returns_current_user_for_each_role() throws Exception {
        for (String role : new String[] {"VIEWER", "EDITOR", "ADMIN"}) {
            String email = uniqueEmail();
            String id = AuthHttp.createUserId(port, email, role);
            String cookie = AuthHttp.login(port, email, PASSWORD);
            Resp r = AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null);
            assertThat(r.status()).isEqualTo(200);
            JsonNode n = r.json();
            assertThat(n.path("user_id").asString()).isEqualTo(id);
            assertThat(n.path("email").asString()).isEqualTo(email.toLowerCase());
            assertThat(n.path("display_name").asString()).isEqualTo("QA " + role);
            assertThat(n.path("role").asString()).isEqualTo(role);
            assertThat(n.has("username")).isFalse();
            assertThat(n.has("password_hash")).isFalse();
            assertThat(n.has("password")).isFalse();
        }
    }

    @Test
    void me_rejects_bearer_and_missing_or_fake_session() throws Exception {
        assertProblem(AuthHttp.bearer(port, "GET", "/api/v1/auth/me", ADMIN_KEY, null), 403, "FORBIDDEN");
        assertProblem(AuthHttp.bearer(port, "GET", "/api/v1/auth/me", INGEST_KEY, null), 403, "FORBIDDEN");
        assertProblem(AuthHttp.bearer(port, "GET", "/api/v1/auth/me", null, null), 401, "UNAUTHENTICATED");
        assertProblem(
                AuthHttp.session(port, "GET", "/api/v1/auth/me", "CDP_SESSION=bogus", null), 401, "UNAUTHENTICATED");
    }

    // ---- logout ----

    @Test
    void logout_without_session_is_204() throws Exception {
        assertThat(AuthHttp.sendRaw(port, "POST", "/api/v1/auth/logout", null, null).status())
                .isEqualTo(204);
    }

    @Test
    void logout_clears_cookie_and_invalidates_session_without_csrf_header() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "EDITOR");
        // 登出免 CSRF：只帶 Cookie
        Resp out = AuthHttp.sendRaw(port, "POST", "/api/v1/auth/logout", java.util.Map.of("Cookie", cookie), null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookie("CDP_SESSION")).containsPattern("(?i)Max-Age=0|Expires=Thu, 01 Jan 1970");
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null), 401, "UNAUTHENTICATED");
    }

    // ---- password ----

    private Resp changePassword(String cookie, String current, String next) throws Exception {
        return AuthHttp.session(
                port,
                "POST",
                "/api/v1/auth/password",
                cookie,
                "{\"current_password\":\"" + current + "\",\"new_password\":\"" + next + "\"}");
    }

    @Test
    void password_change_works_and_keeps_other_sessions() throws Exception {
        String email = uniqueEmail();
        AuthHttp.createUserId(port, email, "EDITOR");
        String a = AuthHttp.login(port, email, PASSWORD);
        String b = AuthHttp.login(port, email, PASSWORD);
        String next = "another-long-password-9";
        assertThat(changePassword(a, PASSWORD, next).status()).isEqualTo(204);
        assertProblem(login(email, PASSWORD), 401, "INVALID_CREDENTIALS");
        assertThat(login(email, next).status()).isEqualTo(204);
        // 其他 session 不登出，本身也還有效
        assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", b, null).status())
                .isEqualTo(200);
        assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", a, null).status())
                .isEqualTo(200);
    }

    @Test
    void password_change_wrong_current_is_401_invalid_credentials() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        assertProblem(changePassword(cookie, "not-the-current-one", "another-long-password-9"), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void password_rules_12_to_72_bytes() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        String cur = PASSWORD;
        assertProblem(changePassword(cookie, cur, "a".repeat(11)), 400, "VALIDATION_FAILED");
        assertProblem(changePassword(cookie, cur, "a".repeat(73)), 400, "VALIDATION_FAILED");
        // 25 個中文字 = 75 位元組 > 72
        assertProblem(changePassword(cookie, cur, "密".repeat(25)), 400, "VALIDATION_FAILED");
        assertProblem(
                AuthHttp.session(port, "POST", "/api/v1/auth/password", cookie, "{\"current_password\":\"" + cur + "\"}"),
                400,
                "VALIDATION_FAILED");
        // 邊界：12 字元、72 位元組（24 個中文字）可用；不檢查複雜度
        assertThat(changePassword(cookie, cur, "a".repeat(12)).status()).isEqualTo(204);
        assertThat(changePassword(cookie, "a".repeat(12), "密".repeat(24)).status()).isEqualTo(204);
        assertThat(changePassword(cookie, "密".repeat(24), "b".repeat(72)).status()).isEqualTo(204);
    }

    @Test
    void password_requires_csrf_and_session() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        String body = "{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"another-long-password-9\"}";
        Resp noHeader =
                AuthHttp.sendRaw(port, "POST", "/api/v1/auth/password", java.util.Map.of("Cookie", cookie), body);
        assertProblem(noHeader, 403, "CSRF_INVALID");
        assertProblem(AuthHttp.bearer(port, "POST", "/api/v1/auth/password", ADMIN_KEY, body), 403, "FORBIDDEN");
        assertProblem(AuthHttp.bearer(port, "POST", "/api/v1/auth/password", null, body), 401, "UNAUTHENTICATED");
    }

    // ---- 停用、改角色下一請求生效 ----

    @Test
    void disabling_user_invalidates_existing_session_on_next_request() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "EDITOR");
        String cookie = AuthHttp.login(port, email, PASSWORD);
        assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null).status())
                .isEqualTo(200);
        assertThat(AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, "{\"status\":\"DISABLED\"}")
                        .status())
                .isEqualTo(200);
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null), 401, "UNAUTHENTICATED");
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/enrollments?q=x", cookie, null), 401, "UNAUTHENTICATED");
        assertProblem(login(email, PASSWORD), 401, "INVALID_CREDENTIALS");
        // 重新啟用後可再登入
        assertThat(AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, "{\"status\":\"ACTIVE\"}")
                        .status())
                .isEqualTo(200);
        assertThat(login(email, PASSWORD).status()).isEqualTo(204);
    }

    @Test
    void role_change_takes_effect_on_next_request() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String cookie = AuthHttp.login(port, email, PASSWORD);
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/users", cookie, null), 403, "FORBIDDEN");

        assertThat(AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, "{\"role\":\"ADMIN\"}")
                        .status())
                .isEqualTo(200);
        assertThat(AuthHttp.session(port, "GET", "/api/v1/users", cookie, null).status())
                .isEqualTo(200);
        assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null)
                        .json()
                        .path("role")
                        .asString())
                .isEqualTo("ADMIN");

        assertThat(AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, "{\"role\":\"VIEWER\"}")
                        .status())
                .isEqualTo(200);
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/users", cookie, null), 403, "FORBIDDEN");
        assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null)
                        .json()
                        .path("role")
                        .asString())
                .isEqualTo("VIEWER");
    }

    @Test
    void admin_password_reset_via_patch_replaces_password() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String next = "reset-by-admin-123";
        assertThat(AuthHttp.bearer(
                                port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, "{\"password\":\"" + next + "\"}")
                        .status())
                .isEqualTo(200);
        assertProblem(login(email, PASSWORD), 401, "INVALID_CREDENTIALS");
        assertThat(login(email, next).status()).isEqualTo(204);
    }
}
