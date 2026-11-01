package com.cdp.acceptance.userauth;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.PASSWORD;
import static com.cdp.acceptance.security.AuthHttp.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * user-auth-api 驗收：auth.md §4 {@code LAST_ADMIN}（含並行）。這個 context 專屬（屬性 {@code userauth.ctx} 避免與他類共用資料庫），
 * 每個測試前清空 {@code app_user}，才能精確控制「啟用中的 ADMIN」數量。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "userauth.ctx=last-admin"})
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class LastAdminAcceptanceTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from app_user");
    }

    private String admin() throws Exception {
        return AuthHttp.createUserId(port, uniqueEmail(), "ADMIN");
    }

    private Resp patch(String id, String body) throws Exception {
        return AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, body);
    }

    private void assertLastAdmin(Resp r) {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("LAST_ADMIN");
        assertThat(r.json().path("detail").asString()).isEqualTo("至少要保留一位啟用中的管理員。");
        assertThat(r.json().path("request_id").asString()).isNotBlank();
    }

    private int activeAdmins() {
        return jdbc.queryForObject(
                "select count(*) from app_user where role = 'ADMIN' and status = 'ACTIVE'", Integer.class);
    }

    @Test
    void sole_admin_cannot_be_demoted_or_disabled() throws Exception {
        String a = admin();
        assertLastAdmin(patch(a, "{\"role\":\"VIEWER\"}"));
        assertLastAdmin(patch(a, "{\"role\":\"EDITOR\"}"));
        assertLastAdmin(patch(a, "{\"status\":\"DISABLED\"}"));
        assertLastAdmin(patch(a, "{\"role\":\"VIEWER\",\"status\":\"DISABLED\",\"display_name\":\"x\"}"));
        // 被拒的更新不留下任何副作用（同一交易）
        assertThat(jdbc.queryForMap("select role, status, display_name from app_user where user_id = ?", a))
                .containsEntry("role", "ADMIN")
                .containsEntry("status", "ACTIVE")
                .containsEntry("display_name", "QA ADMIN");
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    void sole_admin_may_still_change_unrelated_fields_or_keep_role() throws Exception {
        String a = admin();
        assertThat(patch(a, "{\"display_name\":\"renamed\"}").status()).isEqualTo(200);
        assertThat(patch(a, "{\"role\":\"ADMIN\",\"status\":\"ACTIVE\"}").status()).isEqualTo(200);
        assertThat(patch(a, "{\"password\":\"brand-new-password-1\"}").status()).isEqualTo(200);
    }

    @Test
    void self_demotion_by_session_of_sole_admin_is_rejected() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "ADMIN");
        String cookie = AuthHttp.login(port, email, PASSWORD);
        assertLastAdmin(AuthHttp.session(port, "PATCH", "/api/v1/users/" + id, cookie, "{\"role\":\"VIEWER\"}"));
    }

    @Test
    void second_admin_allows_one_demotion_but_not_the_next() throws Exception {
        String a = admin();
        String b = admin();
        assertThat(patch(a, "{\"role\":\"VIEWER\"}").status()).isEqualTo(200);
        assertLastAdmin(patch(b, "{\"role\":\"VIEWER\"}"));
        assertLastAdmin(patch(b, "{\"status\":\"DISABLED\"}"));
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    void disabled_admins_do_not_count() throws Exception {
        String a = admin();
        String b = admin();
        assertThat(patch(b, "{\"status\":\"DISABLED\"}").status()).isEqualTo(200);
        assertLastAdmin(patch(a, "{\"role\":\"EDITOR\"}"));
        // 重新啟用 b 之後 a 才能降級
        assertThat(patch(b, "{\"status\":\"ACTIVE\"}").status()).isEqualTo(200);
        assertThat(patch(a, "{\"role\":\"EDITOR\"}").status()).isEqualTo(200);
    }

    @Test
    void promoting_another_user_then_demoting_is_allowed() throws Exception {
        String a = admin();
        String v = AuthHttp.createUserId(port, uniqueEmail(), "VIEWER");
        assertLastAdmin(patch(a, "{\"role\":\"VIEWER\"}"));
        assertThat(patch(v, "{\"role\":\"ADMIN\"}").status()).isEqualTo(200);
        assertThat(patch(a, "{\"role\":\"VIEWER\"}").status()).isEqualTo(200);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    void non_admin_users_can_be_disabled_freely_even_without_admins_elsewhere() throws Exception {
        admin();
        String v = AuthHttp.createUserId(port, uniqueEmail(), "VIEWER");
        assertThat(patch(v, "{\"status\":\"DISABLED\"}").status()).isEqualTo(200);
    }

    // ---- 並行 ----

    @Test
    void concurrent_demotions_never_leave_zero_active_admins() throws Exception {
        for (int round = 0; round < 3; round++) {
            jdbc.update("delete from app_user");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 6; i++) ids.add(admin());
            // 對所有 ADMIN 同時降級；剛好一位必須留下
            List<Resp> rs = parallel(ids.size(), i -> () -> patch(ids.get(i), "{\"role\":\"VIEWER\"}"));
            long ok = rs.stream().filter(r -> r.status() == 200).count();
            long last = rs.stream().filter(r -> r.status() == 409 && "LAST_ADMIN".equals(r.code())).count();
            assertThat(ok).as("round %d: %s", round, rs.stream().map(Resp::status).toList()).isEqualTo(5);
            assertThat(last).isEqualTo(1);
            assertThat(activeAdmins()).isEqualTo(1);
        }
    }

    @Test
    void concurrent_mixed_disable_and_demote_never_leave_zero_active_admins() throws Exception {
        for (int round = 0; round < 3; round++) {
            jdbc.update("delete from app_user");
            String a = admin();
            String b = admin();
            List<Resp> rs = parallel(2, i -> () -> i == 0
                    ? patch(a, "{\"status\":\"DISABLED\"}")
                    : patch(b, "{\"role\":\"EDITOR\"}"));
            assertThat(rs.stream().filter(r -> r.status() == 200).count()).as(rs.toString()).isEqualTo(1);
            assertThat(rs.stream().filter(r -> "LAST_ADMIN".equals(r.code())).count()).isEqualTo(1);
            assertThat(activeAdmins()).isEqualTo(1);
        }
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
