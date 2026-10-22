package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.acceptance.security.SecurityTestSupport;

import tools.jackson.databind.JsonNode;

/** user-auth-api 驗收：auth.md §4 {@code /users} 建立、列出、修改與錯誤碼。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class UsersApiAcceptanceTest {

    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

    @LocalServerPort int port;

    private Resp create(String email, String name, String role, String password) throws Exception {
        return AuthHttp.createUser(port, email, name, role, password);
    }

    private Resp patch(String id, String body) throws Exception {
        return AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, body);
    }

    private void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.json().path("status").asInt()).isEqualTo(status);
        assertThat(r.code()).isEqualTo(code);
        assertThat(r.json().path("request_id").asString()).isNotBlank();
    }

    private void assertUserShape(JsonNode u) {
        assertThat(u.path("user_id").asString()).matches(ULID);
        assertThat(u.path("email").asString()).isNotBlank();
        assertThat(u.path("display_name").asString()).isNotBlank();
        assertThat(u.path("role").asString()).isIn("VIEWER", "EDITOR", "ADMIN");
        assertThat(u.path("status").asString()).isIn("ACTIVE", "DISABLED");
        Instant.parse(u.path("created_at").asString());
        Instant.parse(u.path("updated_at").asString());
        assertThat(u.has("password")).isFalse();
        assertThat(u.has("password_hash")).isFalse();
    }

    // ---- POST ----

    @Test
    void create_returns_201_user_with_lowercase_email_and_active_status() throws Exception {
        String email = "Mix" + System.nanoTime() + "@Example.COM";
        Resp r = create(email, "王小明", "EDITOR", PASSWORD);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode u = r.json();
        assertUserShape(u);
        assertThat(u.path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(u.path("display_name").asString()).isEqualTo("王小明");
        assertThat(u.path("role").asString()).isEqualTo("EDITOR");
        assertThat(u.path("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void create_works_for_admin_session_with_csrf() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        Resp r = AuthHttp.session(
                port,
                "POST",
                "/api/v1/users",
                cookie,
                AuthHttp.userBody(uniqueEmail(), "by session", "VIEWER", PASSWORD));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
    }

    @Test
    void create_duplicate_email_is_409_email_taken_case_insensitive() throws Exception {
        String email = uniqueEmail();
        assertThat(create(email, "a", "VIEWER", PASSWORD).status()).isEqualTo(201);
        assertProblem(create(email, "b", "ADMIN", PASSWORD), 409, "EMAIL_TAKEN");
        assertProblem(create(email.toUpperCase(), "c", "VIEWER", PASSWORD), 409, "EMAIL_TAKEN");
    }

    @Test
    void concurrent_create_of_same_email_yields_one_201_and_rest_409() throws Exception {
        String email = uniqueEmail();
        List<Resp> rs = parallel(
                5, i -> () -> create(email, "race " + i, "VIEWER", PASSWORD));
        assertThat(rs.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.status() == 409 && "EMAIL_TAKEN".equals(r.code())).count())
                .isEqualTo(4);
    }

    @Test
    void create_validation() throws Exception {
        String ok = uniqueEmail();
        String[] bad = {
            AuthHttp.userBody("not-an-email", "n", "VIEWER", PASSWORD),
            AuthHttp.userBody(ok, "n", "SUPERUSER", PASSWORD),
            AuthHttp.userBody(ok, "n", "viewer", PASSWORD),
            AuthHttp.userBody(ok, "", "VIEWER", PASSWORD),
            AuthHttp.userBody(ok, "x".repeat(101), "VIEWER", PASSWORD),
            AuthHttp.userBody(ok, "n", "VIEWER", "a".repeat(11)),
            AuthHttp.userBody(ok, "n", "VIEWER", "a".repeat(73)),
            AuthHttp.userBody(ok, "n", "VIEWER", "密".repeat(25)),
            "{\"display_name\":\"n\",\"role\":\"VIEWER\",\"password\":\"" + PASSWORD + "\"}",
            "{\"email\":\"" + ok + "\",\"role\":\"VIEWER\",\"password\":\"" + PASSWORD + "\"}",
            "{\"email\":\"" + ok + "\",\"display_name\":\"n\",\"password\":\"" + PASSWORD + "\"}",
            "{\"email\":\"" + ok + "\",\"display_name\":\"n\",\"role\":\"VIEWER\"}",
            "{}"
        };
        for (String b : bad) {
            Resp r = AuthHttp.bearer(port, "POST", "/api/v1/users", ADMIN_KEY, b);
            assertProblem(r, 400, "VALIDATION_FAILED");
        }
        // 邊界可用：display_name 100 字、密碼 12 字元
        assertThat(create(ok, "x".repeat(100), "VIEWER", "a".repeat(12)).status()).isEqualTo(201);
        // 驗證失敗不建立帳號：同 email 之後仍可建
        String ok2 = uniqueEmail();
        assertProblem(create(ok2, "n", "SUPERUSER", PASSWORD), 400, "VALIDATION_FAILED");
        assertThat(create(ok2, "n", "VIEWER", PASSWORD).status()).isEqualTo(201);
    }

    @Test
    void auth_is_checked_before_role_before_input() throws Exception {
        String badBody = "{\"role\":\"SUPERUSER\"}";
        assertProblem(AuthHttp.sendRaw(port, "POST", "/api/v1/users", null, badBody), 401, "UNAUTHENTICATED");
        String viewer = AuthHttp.loginNewUser(port, "VIEWER");
        assertProblem(AuthHttp.session(port, "POST", "/api/v1/users", viewer, badBody), 403, "FORBIDDEN");
        assertProblem(AuthHttp.bearer(port, "POST", "/api/v1/users", AuthHttp.INGEST_KEY, badBody), 403, "FORBIDDEN");
    }

    // ---- GET ----

    @Test
    void list_is_sorted_by_email_includes_disabled_and_hides_secrets() throws Exception {
        String tag = "list" + System.nanoTime();
        String c = tag + "c@example.com";
        String a = tag + "a@example.com";
        String b = tag + "b@example.com";
        create(c, "C", "VIEWER", PASSWORD);
        String aId = create(a, "A", "EDITOR", PASSWORD).json().path("user_id").asString();
        create(b, "B", "ADMIN", PASSWORD);
        assertThat(patch(aId, "{\"status\":\"DISABLED\"}").status()).isEqualTo(200);

        Resp r = AuthHttp.bearer(port, "GET", "/api/v1/users", ADMIN_KEY, null);
        assertThat(r.status()).isEqualTo(200);
        JsonNode items = r.json().path("items");
        assertThat(items.isArray()).isTrue();
        List<String> emails = new ArrayList<>();
        for (JsonNode u : items) {
            assertUserShape(u);
            emails.add(u.path("email").asString());
        }
        assertThat(emails).isSorted();
        assertThat(emails.stream().filter(e -> e.startsWith(tag)).toList()).containsExactly(a, b, c);
        for (JsonNode u : items) {
            if (u.path("email").asString().equals(a)) assertThat(u.path("status").asString()).isEqualTo("DISABLED");
        }
    }

    @Test
    void list_works_for_admin_session() throws Exception {
        String cookie = AuthHttp.loginNewUser(port, "ADMIN");
        Resp r = AuthHttp.session(port, "GET", "/api/v1/users", cookie, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("items").isArray()).isTrue();
    }

    // ---- PATCH ----

    @Test
    void patch_updates_each_field_and_bumps_updated_at() throws Exception {
        Resp created = create(uniqueEmail(), "before", "VIEWER", PASSWORD);
        JsonNode u0 = created.json();
        String id = u0.path("user_id").asString();
        Thread.sleep(20);

        Resp r = patch(id, "{\"display_name\":\"after\",\"role\":\"EDITOR\",\"status\":\"DISABLED\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode u1 = r.json();
        assertUserShape(u1);
        assertThat(u1.path("user_id").asString()).isEqualTo(id);
        assertThat(u1.path("email").asString()).isEqualTo(u0.path("email").asString());
        assertThat(u1.path("display_name").asString()).isEqualTo("after");
        assertThat(u1.path("role").asString()).isEqualTo("EDITOR");
        assertThat(u1.path("status").asString()).isEqualTo("DISABLED");
        assertThat(u1.path("created_at").asString()).isEqualTo(u0.path("created_at").asString());
        assertThat(Instant.parse(u1.path("updated_at").asString()))
                .isAfter(Instant.parse(u0.path("updated_at").asString()));

        // 部分更新：只送 status 不動其他欄位
        JsonNode u2 = patch(id, "{\"status\":\"ACTIVE\"}").json();
        assertThat(u2.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(u2.path("display_name").asString()).isEqualTo("after");
        assertThat(u2.path("role").asString()).isEqualTo("EDITOR");
    }

    @Test
    void patch_not_found_is_404_user_not_found() throws Exception {
        assertProblem(patch("01JXXXXXXXXXXXXXXXXXXXXXXX", "{\"display_name\":\"x\"}"), 404, "USER_NOT_FOUND");
    }

    @Test
    void patch_validation() throws Exception {
        String id = create(uniqueEmail(), "n", "VIEWER", PASSWORD).json().path("user_id").asString();
        for (String b : new String[] {
            "{\"role\":\"SUPERUSER\"}",
            "{\"status\":\"DELETED\"}",
            "{\"display_name\":\"\"}",
            "{\"display_name\":\"" + "x".repeat(101) + "\"}",
            "{\"password\":\"" + "a".repeat(11) + "\"}",
            "{\"password\":\"" + "a".repeat(73) + "\"}"
        }) {
            assertProblem(patch(id, b), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void patch_requires_admin_and_csrf_for_session() throws Exception {
        String id = create(uniqueEmail(), "n", "VIEWER", PASSWORD).json().path("user_id").asString();
        String editor = AuthHttp.loginNewUser(port, "EDITOR");
        assertProblem(
                AuthHttp.session(port, "PATCH", "/api/v1/users/" + id, editor, "{\"display_name\":\"x\"}"),
                403,
                "FORBIDDEN");
        String admin = AuthHttp.loginNewUser(port, "ADMIN");
        Resp noCsrf = AuthHttp.sendRaw(
                port,
                "PATCH",
                "/api/v1/users/" + id,
                java.util.Map.of("Cookie", admin),
                "{\"display_name\":\"x\"}");
        assertProblem(noCsrf, 403, "CSRF_INVALID");
        assertThat(AuthHttp.session(port, "PATCH", "/api/v1/users/" + id, admin, "{\"display_name\":\"x\"}")
                        .status())
                .isEqualTo(200);
    }

    // ---- helpers ----

    interface Task {
        Callable<Resp> at(int i);
    }

    private List<Resp> parallel(int n, Task t) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Resp> c = t.at(i);
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
