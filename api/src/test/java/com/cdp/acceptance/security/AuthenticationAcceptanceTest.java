package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * user-auth-api 改寫（auth.md）：登入改 email＋密碼，帳號由管理金鑰經 POST /users 建立（VIEWER 取代 v0.1.0 的 SUPPORT）；
 * session 寫入請求自動帶 X-XSRF-TOKEN。測試意圖不變。
 *
 * <p>BE-04 驗收：契約 §1.1、§1.2、§5。只驗認證層：已通過認證的請求會進 controller（BE-01 未合併時為 500／400 等），
 * 故一律只斷言「不是 401／403」。
 *
 * <p>閒置逾時（CDP_SESSION_IDLE_TIMEOUT）：Tomcat 逾時最小單位為分鐘，驗收測試無法快轉，改由 worker 單元測試與 verifier 手動驗證。
 * Secure 屬性：application-test.yml 把 session cookie secure 設為 false，故不在此斷言。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class AuthenticationAcceptanceTest {

    private static final String INGEST = "Bearer test-ingest-key";
    private static final String ADMIN = "Bearer test-admin-key";
    private static final String PASS = AuthHttp.PASSWORD;
    private static final String ID = "01JXXXXXXXXXXXXXXXXXXXXXXX";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort int port;

    private String user;

    @BeforeEach
    void createViewer() throws Exception {
        user = AuthHttp.uniqueEmail();
        AuthHttp.createUserId(port, user, "VIEWER");
    }

    private HttpResponse<String> call(String method, String path, String auth, String cookie, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (auth != null) b.header("Authorization", auth);
        if (cookie != null) b.header("Cookie", cookie);
        if (cookie != null && !"GET".equals(method) && AuthHttp.xsrf(cookie) != null) {
            b.header("X-XSRF-TOKEN", AuthHttp.xsrf(cookie));
        }
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String creds(String email, String pass) {
        return AuthHttp.credentials(email, pass);
    }

    /** 登入並以 /auth/me 取得 XSRF-TOKEN；回傳 "CDP_SESSION=…; XSRF-TOKEN=…"。 */
    private String login() throws Exception {
        return AuthHttp.login(port, user, PASS);
    }

    private String setCookie(HttpResponse<String> r) {
        return r.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("CDP_SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CDP_SESSION Set-Cookie"));
    }

    private String sessionCookie(HttpResponse<String> r) {
        return setCookie(r).split(";")[0];
    }

    private void assertProblem(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode n = json.readTree(r.body());
        assertThat(n.path("type").asText()).isEqualTo("about:blank");
        assertThat(n.path("status").asInt()).isEqualTo(status);
        assertThat(n.path("code").asText()).isEqualTo(code);
        assertThat(n.path("request_id").asText()).isNotBlank();
        assertThat(n.path("request_id").asText())
                .isEqualTo(r.headers().firstValue("X-Request-Id").orElse(null));
    }

    private void assertReachedController(HttpResponse<String> r) {
        assertThat(r.statusCode()).isNotIn(401, 403);
    }

    private static final List<String[]> PROTECTED = List.of(
            new String[] {"POST", "/api/v1/events", "{}"},
            new String[] {"POST", "/api/v1/events/batch", "{}"},
            new String[] {"POST", "/api/v1/journeys", "{}"},
            new String[] {"GET", "/api/v1/enrollments?q=x", null},
            new String[] {"GET", "/api/v1/enrollments/" + ID, null},
            new String[] {"GET", "/api/v1/auth/me", null});

    // ---- 401 ----

    @Test
    void unauthenticated_is_401_problem_json_with_www_authenticate() throws Exception {
        for (String[] p : PROTECTED) {
            HttpResponse<String> r = call(p[0], p[1], null, null, p[2]);
            assertProblem(r, 401, "UNAUTHENTICATED");
            assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).isEqualTo("Bearer");
        }
    }

    @Test
    void wrong_key_is_401() throws Exception {
        for (String auth : new String[] {"Bearer wrong", "Bearer ", "Basic dGVzdDp0ZXN0", "Bearer test-admin-keyX"}) {
            assertProblem(call("GET", "/api/v1/enrollments?q=x", auth, null, null), 401, "UNAUTHENTICATED");
        }
    }

    @Test
    void authentication_is_checked_before_input_and_routing() throws Exception {
        assertProblem(call("POST", "/api/v1/events", null, null, "not json"), 401, "UNAUTHENTICATED");
        assertProblem(call("GET", "/api/v1/enrollments", null, null, null), 401, "UNAUTHENTICATED");
        assertProblem(call("GET", "/api/v1/no-such-path", null, null, null), 401, "UNAUTHENTICATED");
    }

    @Test
    void request_id_is_echoed_on_401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/me"))
                .header("X-Request-Id", "req-abc.123")
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("X-Request-Id")).hasValue("req-abc.123");
        assertThat(json.readTree(r.body()).path("request_id").asText()).isEqualTo("req-abc.123");
    }

    // ---- 角色矩陣 ----

    @Test
    void ingest_key_only_for_event_ingestion() throws Exception {
        assertReachedController(call("POST", "/api/v1/events", INGEST, null, "{}"));
        assertReachedController(call("POST", "/api/v1/events/batch", INGEST, null, "{}"));
        assertProblem(call("POST", "/api/v1/journeys", INGEST, null, "{}"), 403, "FORBIDDEN");
        assertProblem(call("GET", "/api/v1/enrollments?q=x", INGEST, null, null), 403, "FORBIDDEN");
    }

    @Test
    void admin_key_for_journeys_and_queries_but_not_events() throws Exception {
        assertReachedController(call("POST", "/api/v1/journeys", ADMIN, null, "{}"));
        assertReachedController(call("GET", "/api/v1/enrollments?q=x", ADMIN, null, null));
        assertProblem(call("POST", "/api/v1/events", ADMIN, null, "{}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/api/v1/events/batch", ADMIN, null, "{}"), 403, "FORBIDDEN");
    }

    @Test
    void viewer_session_is_read_only() throws Exception {
        String cookie = login();
        assertReachedController(call("GET", "/api/v1/enrollments?q=x", null, cookie, null));
        assertReachedController(call("GET", "/api/v1/enrollments/" + ID, null, cookie, null));
        assertProblem(call("POST", "/api/v1/journeys", null, cookie, "{}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/api/v1/events", null, cookie, "{}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/api/v1/events/batch", null, cookie, "{}"), 403, "FORBIDDEN");
    }

    @Test
    void authorization_header_wins_over_cookie() throws Exception {
        String cookie = login();
        assertProblem(call("GET", "/api/v1/enrollments?q=x", "Bearer wrong", cookie, null), 401, "UNAUTHENTICATED");
        assertProblem(call("GET", "/api/v1/enrollments?q=x", INGEST, cookie, null), 403, "FORBIDDEN");
    }

    // ---- 登入／登出／me ----

    @Test
    void login_success_sets_hardened_cookie() throws Exception {
        HttpResponse<String> r = call("POST", "/api/v1/auth/login", null, null, creds(user, PASS));
        assertThat(r.statusCode()).isEqualTo(204);
        String sc = setCookie(r);
        assertThat(sc).containsIgnoringCase("HttpOnly");
        assertThat(sc).containsIgnoringCase("SameSite=Strict");
        assertThat(sc).contains("Path=/api");
        assertThat(sc).doesNotContain("Path=/api/");
    }

    @Test
    void each_login_issues_a_new_session_id() throws Exception {
        String a = AuthHttp.cookieValue(login(), "CDP_SESSION");
        assertThat(AuthHttp.cookieValue(login(), "CDP_SESSION")).isNotEqualTo(a);
        // 帶著舊 cookie 再登入也要換新 id（防 session fixation）
        HttpResponse<String> r = call("POST", "/api/v1/auth/login", null, "CDP_SESSION=" + a, creds(user, PASS));
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(sessionCookie(r)).isNotEqualTo("CDP_SESSION=" + a);
    }

    @Test
    void bad_credentials_are_401_invalid_credentials_and_indistinguishable() throws Exception {
        HttpResponse<String> wrongPass = call("POST", "/api/v1/auth/login", null, null, creds(user, "nope"));
        HttpResponse<String> wrongUser = call("POST", "/api/v1/auth/login", null, null, creds("nobody@example.com", PASS));
        assertProblem(wrongPass, 401, "INVALID_CREDENTIALS");
        assertProblem(wrongUser, 401, "INVALID_CREDENTIALS");
        assertThat(json.readTree(wrongPass.body()).path("detail"))
                .isEqualTo(json.readTree(wrongUser.body()).path("detail"));
        assertThat(wrongPass.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("CDP_SESSION="));
    }

    @Test
    void login_validation_and_media_type() throws Exception {
        HttpResponse<String> missing =
                call("POST", "/api/v1/auth/login", null, null, "{\"email\":\"" + user + "\"}");
        assertProblem(missing, 400, "VALIDATION_FAILED");
        assertThat(json.readTree(missing.body()).path("errors").toString()).contains("password");
        assertProblem(call("POST", "/api/v1/auth/login", null, null, creds("", "")), 400, "VALIDATION_FAILED");

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "text/plain")
                .POST(BodyPublishers.ofString("email=a&password=b"))
                .build();
        assertProblem(http.send(req, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void me_returns_current_user_with_session() throws Exception {
        HttpResponse<String> r = call("GET", "/api/v1/auth/me", null, login(), null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body()).path("email").asText()).isEqualTo(user.toLowerCase());
        assertThat(json.readTree(r.body()).path("role").asText()).isEqualTo("VIEWER");
    }

    @Test
    void fake_session_cookie_is_401() throws Exception {
        assertProblem(call("GET", "/api/v1/auth/me", null, "CDP_SESSION=bogus", null), 401, "UNAUTHENTICATED");
    }

    @Test
    void logout_is_204_clears_cookie_and_invalidates_session() throws Exception {
        String cookie = login();
        HttpResponse<String> r = call("POST", "/api/v1/auth/logout", null, cookie, null);
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(setCookie(r)).containsPattern("(?i)Max-Age=0|Expires=Thu, 01 Jan 1970");
        assertProblem(call("GET", "/api/v1/auth/me", null, cookie, null), 401, "UNAUTHENTICATED");
    }

    @Test
    void logout_without_session_is_204() throws Exception {
        assertThat(call("POST", "/api/v1/auth/logout", null, null, null).statusCode()).isEqualTo(204);
    }
}
