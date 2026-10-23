package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
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
 * user-auth-api 驗收：auth.md §2 角色矩陣。「放行」只斷言認證與角色層通過（不是 401／403；controller 可能回 400／404／尚未實作），
 * 「拒絕」斷言 403 FORBIDDEN 的 problem+json。session 的寫入請求自動帶 CSRF 標頭，所以 403 一定是角色不足。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class RoleMatrixAcceptanceTest {

    private static final String ID = "01JXXXXXXXXXXXXXXXXXXXXXXX";

    @LocalServerPort int port;

    private static final Map<String, String> COOKIES = new LinkedHashMap<>();
    private static int staticPort;

    /** 憑證名稱：INGEST、ADMIN_KEY、VIEWER、EDITOR、ADMIN（session）。 */
    private static final List<String> CREDS = List.of("INGEST", "ADMIN_KEY", "VIEWER", "EDITOR", "ADMIN");

    private record Api(String method, String path, String body, List<String> allowed) {}

    private static final List<String> READERS = List.of("ADMIN_KEY", "VIEWER", "EDITOR", "ADMIN");
    private static final List<String> WRITERS = List.of("EDITOR", "ADMIN");
    private static final List<String> BEARER_ADMIN_ONLY = List.of("ADMIN_KEY");

    private static final List<Api> MATRIX = List.of(
            new Api("POST", "/api/v1/events", "{}", List.of("INGEST")),
            new Api("POST", "/api/v1/events/batch", "{}", List.of("INGEST")),
            // PUT profiles：INGEST 與管理金鑰；ADMIN session 不可
            new Api("PUT", "/api/v1/profiles/" + ID, "{}", List.of("INGEST", "ADMIN_KEY")),
            new Api("GET", "/api/v1/enrollments?q=x", null, READERS),
            new Api("GET", "/api/v1/enrollments/" + ID, null, READERS),
            new Api("GET", "/api/v1/enrollments/" + ID + "/deliveries", null, READERS),
            new Api("GET", "/api/v1/profiles/search?q=x", null, READERS),
            new Api("GET", "/api/v1/profiles/" + ID, null, READERS),
            new Api("GET", "/api/v1/profiles/" + ID + "/enrollments", null, READERS),
            new Api("GET", "/api/v1/journeys", null, READERS),
            new Api("GET", "/api/v1/journeys/" + ID, null, READERS),
            new Api("GET", "/api/v1/journeys/" + ID + "/versions/1", null, READERS),
            new Api("GET", "/api/v1/journeys/" + ID + "/draft", null, READERS),
            new Api("GET", "/api/v1/journeys/" + ID + "/reviews", null, READERS),
            // POST /journeys：只有管理金鑰，session（含 ADMIN）不可
            new Api("POST", "/api/v1/journeys", "{}", BEARER_ADMIN_ONLY),
            // 草稿寫入、validate、送審：EDITOR 以上的 session
            new Api("POST", "/api/v1/journeys/drafts", "{}", WRITERS),
            new Api("PUT", "/api/v1/journeys/" + ID + "/draft", "{}", WRITERS),
            new Api("DELETE", "/api/v1/journeys/" + ID + "/draft", null, WRITERS),
            new Api("POST", "/api/v1/journeys/" + ID + "/draft/validate", null, WRITERS),
            new Api("POST", "/api/v1/journeys/" + ID + "/draft/submit", "{}", WRITERS),
            // 停止（D-060 不經審核）：EDITOR 以上
            new Api("POST", "/api/v1/journeys/" + ID + "/stop", "{}", WRITERS),
            // withdraw：EDITOR（僅自己送審的，由 journeys 單驗）與 ADMIN；此處只驗角色層
            new Api("POST", "/api/v1/journeys/" + ID + "/reviews/" + ID + "/withdraw", "{}", WRITERS),
            // approve／reject：只有 ADMIN session
            new Api("POST", "/api/v1/journeys/" + ID + "/reviews/" + ID + "/approve", "{}", List.of("ADMIN")),
            new Api("POST", "/api/v1/journeys/" + ID + "/reviews/" + ID + "/reject", "{}", List.of("ADMIN")),
            // /users：ADMIN session 與管理金鑰
            new Api("GET", "/api/v1/users", null, List.of("ADMIN_KEY", "ADMIN")),
            new Api("POST", "/api/v1/users", "{}", List.of("ADMIN_KEY", "ADMIN")),
            new Api("PATCH", "/api/v1/users/" + ID, "{}", List.of("ADMIN_KEY", "ADMIN")));

    private String cookie(String role) throws Exception {
        synchronized (COOKIES) {
            if (staticPort != port) {
                COOKIES.clear();
                staticPort = port;
            }
            String c = COOKIES.get(role);
            if (c == null) {
                c = AuthHttp.loginNewUser(port, role);
                COOKIES.put(role, c);
            }
            return c;
        }
    }

    private Resp call(String cred, Api api) throws Exception {
        return switch (cred) {
            case "INGEST" -> AuthHttp.bearer(port, api.method(), api.path(), INGEST_KEY, api.body());
            case "ADMIN_KEY" -> AuthHttp.bearer(port, api.method(), api.path(), ADMIN_KEY, api.body());
            default -> AuthHttp.session(port, api.method(), api.path(), cookie(cred), api.body());
        };
    }

    @Test
    void matrix_allows_and_denies_exactly_as_listed() throws Exception {
        for (Api api : MATRIX) {
            for (String cred : CREDS) {
                Resp r = call(cred, api);
                String what = cred + " " + api.method() + " " + api.path() + " -> " + r.status() + " " + r.body();
                if (api.allowed().contains(cred)) {
                    assertThat(r.status()).as(what).isNotIn(401, 403);
                } else {
                    assertThat(r.status()).as(what).isEqualTo(403);
                    assertThat(r.code()).as(what).isEqualTo("FORBIDDEN");
                    assertThat(r.headers().firstValue("Content-Type").orElse(""))
                            .as(what)
                            .startsWith("application/problem+json");
                }
            }
        }
    }

    @Test
    void unauthenticated_is_401_on_every_listed_api() throws Exception {
        for (Api api : MATRIX) {
            Resp r = AuthHttp.sendRaw(port, api.method(), api.path(), null, api.body());
            assertThat(r.status()).as(api.method() + " " + api.path()).isEqualTo(401);
            assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void review_actions_reject_every_bearer_including_admin_key() throws Exception {
        for (String action : List.of("withdraw", "approve", "reject")) {
            String path = "/api/v1/journeys/" + ID + "/reviews/" + ID + "/" + action;
            for (String key : List.of(ADMIN_KEY, INGEST_KEY)) {
                Resp r = AuthHttp.bearer(port, "POST", path, key, "{}");
                assertThat(r.status()).as(action + " " + key).isEqualTo(403);
                assertThat(r.code()).isEqualTo("FORBIDDEN");
            }
        }
        Resp submit = AuthHttp.bearer(port, "POST", "/api/v1/journeys/" + ID + "/draft/submit", ADMIN_KEY, "{}");
        assertThat(submit.status()).isEqualTo(403);
    }

    // ---- Bearer 不看 cookie ----

    @Test
    void authorization_header_wins_over_cookie() throws Exception {
        String admin = cookie("ADMIN");
        // ADMIN session + INGEST Bearer：以 INGEST 論，不得用 /users
        Resp r = AuthHttp.send(port, "GET", "/api/v1/users", INGEST_KEY, admin, null);
        assertThat(r.status()).isEqualTo(403);
        // ADMIN session + 錯誤 Bearer：401，不 fallback 到 cookie
        Resp bad = AuthHttp.send(port, "GET", "/api/v1/users", "Bearer wrong", admin, null);
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.code()).isEqualTo("UNAUTHENTICATED");
        // VIEWER session + INGEST Bearer 可攝取事件（Bearer 為準）
        Resp ev = AuthHttp.send(port, "POST", "/api/v1/events", INGEST_KEY, cookie("VIEWER"), "{}");
        assertThat(ev.status()).isNotIn(401, 403);
        // ADMIN session + Bearer 的 events 不因 cookie 角色而放行
        Resp ev2 = AuthHttp.send(port, "POST", "/api/v1/events", ADMIN_KEY, admin, "{}");
        assertThat(ev2.status()).isEqualTo(403);
    }

    @Test
    void bearer_requests_need_no_csrf_header() throws Exception {
        Resp r = AuthHttp.sendRaw(
                port, "POST", "/api/v1/events", Map.of("Authorization", INGEST_KEY), "{}");
        assertThat(r.status()).isNotIn(401, 403);
        Resp u = AuthHttp.sendRaw(
                port,
                "POST",
                "/api/v1/users",
                Map.of("Authorization", ADMIN_KEY),
                AuthHttp.userBody(AuthHttp.uniqueEmail(), "no csrf", "VIEWER", AuthHttp.PASSWORD));
        assertThat(u.status()).isEqualTo(201);
    }

    // ---- 未列路徑 ----

    private static final List<String[]> UNLISTED = List.of(
            new String[] {"GET", "/api/v1/not-in-contract"},
            new String[] {"GET", "/api/v1/events"},
            new String[] {"GET", "/api/v1/auth"},
            new String[] {"GET", "/api/unknown"},
            new String[] {"GET", "/actuator"},
            new String[] {"GET", "/actuator/health"},
            new String[] {"GET", "/actuator/env"},
            new String[] {"GET", "/error"},
            new String[] {"GET", "/v3/api-docs"},
            new String[] {"PUT", "/api/v1/enrollments/" + ID},
            new String[] {"DELETE", "/api/v1/enrollments/" + ID},
            new String[] {"DELETE", "/api/v1/users/" + ID},
            new String[] {"GET", "/api/v1/enrollments/" + ID + "/foo"});

    @Test
    void unlisted_paths_are_401_unauthenticated_and_403_for_non_admin_credentials() throws Exception {
        for (String[] p : UNLISTED) {
            Resp anon = AuthHttp.sendRaw(port, p[0], p[1], null, null);
            assertThat(anon.status()).as("anon " + p[0] + " " + p[1]).isEqualTo(401);
            assertThat(anon.code()).isEqualTo("UNAUTHENTICATED");
            for (String cred : List.of("INGEST", "VIEWER", "EDITOR")) {
                Resp r = call(cred, new Api(p[0], p[1], null, List.of()));
                assertThat(r.status()).as(cred + " " + p[0] + " " + p[1]).isEqualTo(403);
                assertThat(r.code()).isEqualTo("FORBIDDEN");
            }
        }
    }

    @Test
    void login_and_logout_need_no_authentication() throws Exception {
        assertThat(AuthHttp.sendRaw(port, "POST", "/api/v1/auth/logout", null, null).status())
                .isEqualTo(204);
        // 登入無憑證不是 401 UNAUTHENTICATED，而是依內容回 400／401 INVALID_CREDENTIALS
        Resp r = AuthHttp.sendRaw(
                port, "POST", "/api/v1/auth/login", null, AuthHttp.credentials("nobody@example.com", "whatever-password"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void auth_endpoints_are_session_only_for_all_roles() throws Exception {
        for (String role : List.of("VIEWER", "EDITOR", "ADMIN")) {
            assertThat(AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie(role), null).status())
                    .as(role)
                    .isEqualTo(200);
        }
    }
}
