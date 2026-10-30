package com.cdp.acceptance.usersessionrevoke;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
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

/** user-session-revoke 驗收：auth.md 第 1 節、第 3 節 /auth/password、第 4 節 PATCH（D-071）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class SessionRevokeAcceptanceTest {

    private static final String NEXT = "another-long-password-9";
    private static final String THIRD = "third-long-password-42";

    @LocalServerPort int port;

    private Resp me(String cookie) throws Exception {
        return AuthHttp.session(port, "GET", "/api/v1/auth/me", cookie, null);
    }

    private void assertAlive(String cookie) throws Exception {
        Resp r = me(cookie);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    private void assertRevoked(String cookie) throws Exception {
        Resp r = me(cookie);
        assertThat(r.status()).as(r.body()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
    }

    private Resp patchByKey(String id, String body) throws Exception {
        return AuthHttp.bearer(port, "PATCH", "/api/v1/users/" + id, ADMIN_KEY, body);
    }

    private Resp patchBySession(String adminCookie, String id, String body) throws Exception {
        return AuthHttp.session(port, "PATCH", "/api/v1/users/" + id, adminCookie, body);
    }

    private Resp rawLogin(String email, String password) throws Exception {
        return AuthHttp.sendRaw(port, "POST", "/api/v1/auth/login", null, credentials(email, password));
    }

    private static String pw(String p) {
        return "{\"password\":\"" + p + "\"}";
    }

    @Test
    void admin_key_reset_revokes_all_existing_sessions_of_that_user() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "EDITOR");
        String a = AuthHttp.login(port, email, PASSWORD);
        String b = AuthHttp.login(port, email, PASSWORD);
        assertAlive(a);
        assertAlive(b);

        Resp r = patchByKey(id, pw(NEXT));
        assertThat(r.status()).as(r.body()).isEqualTo(200);

        assertRevoked(a);
        assertRevoked(b);
        // 寫入請求同樣回 401（未認證先於 CSRF）
        Resp w = AuthHttp.session(
                port,
                "POST",
                "/api/v1/auth/password",
                a,
                "{\"current_password\":\"" + NEXT + "\",\"new_password\":\"yet-another-pass-77\"}");
        assertThat(w.status()).isEqualTo(401);
    }

    @Test
    void admin_session_reset_of_other_user_revokes_target_but_not_admin() throws Exception {
        String admin = AuthHttp.loginNewUser(port, "ADMIN");
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String victim = AuthHttp.login(port, email, PASSWORD);

        Resp r = patchBySession(admin, id, pw(NEXT));
        assertThat(r.status()).as(r.body()).isEqualTo(200);

        assertRevoked(victim);
        assertAlive(admin);
    }

    @Test
    void new_login_after_reset_works_and_next_reset_revokes_it_again() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String old = AuthHttp.login(port, email, PASSWORD);
        assertThat(patchByKey(id, pw(NEXT)).status()).isEqualTo(200);
        assertRevoked(old);

        String fresh = AuthHttp.login(port, email, NEXT);
        assertAlive(fresh);
        assertAlive(fresh);
        assertThat(patchByKey(id, pw(THIRD)).status()).isEqualTo(200);
        assertRevoked(fresh);
    }

    @Test
    void reset_with_password_plus_other_fields_also_revokes() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String s = AuthHttp.login(port, email, PASSWORD);
        Resp r = patchByKey(id, "{\"display_name\":\"renamed\",\"password\":\"" + NEXT + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("display_name").asString()).isEqualTo("renamed");
        assertRevoked(s);
    }

    @Test
    void reset_does_not_affect_other_users_sessions() throws Exception {
        String e1 = uniqueEmail();
        String id1 = AuthHttp.createUserId(port, e1, "VIEWER");
        String other = AuthHttp.loginNewUser(port, "VIEWER");
        String s1 = AuthHttp.login(port, e1, PASSWORD);
        assertThat(patchByKey(id1, pw(NEXT)).status()).isEqualTo(200);
        assertRevoked(s1);
        assertAlive(other);
    }

    @Test
    void admin_resetting_own_password_returns_200_then_own_session_is_revoked() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "ADMIN");
        String self = AuthHttp.login(port, email, PASSWORD);
        String otherDevice = AuthHttp.login(port, email, PASSWORD);

        Resp r = patchBySession(self, id, pw(NEXT));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("user_id").asString()).isEqualTo(id);

        assertRevoked(self);
        assertRevoked(otherDevice);
        assertThat(rawLogin(email, PASSWORD).status()).isEqualTo(401);
        assertAlive(AuthHttp.login(port, email, NEXT));
    }

    @Test
    void patch_without_password_keeps_sessions() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String s = AuthHttp.login(port, email, PASSWORD);

        assertThat(patchByKey(id, "{\"display_name\":\"new name\"}").status()).isEqualTo(200);
        assertAlive(s);
        assertThat(patchByKey(id, "{\"role\":\"EDITOR\"}").status()).isEqualTo(200);
        Resp m = me(s);
        assertThat(m.status()).isEqualTo(200);
        assertThat(m.json().path("role").asString()).isEqualTo("EDITOR");
        assertThat(patchByKey(id, "{\"status\":\"ACTIVE\"}").status()).isEqualTo(200);
        assertAlive(s);
    }

    @Test
    void admin_editing_own_non_password_fields_keeps_own_session() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "ADMIN");
        String self = AuthHttp.login(port, email, PASSWORD);
        assertThat(patchBySession(self, id, "{\"display_name\":\"me again\"}").status())
                .isEqualTo(200);
        assertAlive(self);
    }

    @Test
    void failed_patch_with_password_keeps_sessions_and_old_password() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String s = AuthHttp.login(port, email, PASSWORD);

        assertThat(patchByKey(id, pw("a".repeat(11))).status()).isEqualTo(400);
        assertThat(patchByKey(id, pw("a".repeat(73))).status()).isEqualTo(400);
        assertThat(patchByKey(id, "{\"role\":\"SUPERUSER\",\"password\":\"" + NEXT + "\"}")
                        .status())
                .isEqualTo(400);
        assertThat(patchByKey(id, "{\"status\":\"DELETED\",\"password\":\"" + NEXT + "\"}")
                        .status())
                .isEqualTo(400);
        assertAlive(s);
        assertAlive(AuthHttp.login(port, email, PASSWORD));
        assertThat(rawLogin(email, NEXT).status()).isEqualTo(401);
    }

    @Test
    void patch_unknown_user_with_password_is_404_and_does_not_touch_others() throws Exception {
        String bystander = AuthHttp.loginNewUser(port, "VIEWER");
        Resp r = patchByKey("01JXXXXXXXXXXXXXXXXXXXXXXX", pw(NEXT));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("USER_NOT_FOUND");
        assertAlive(bystander);
    }

    @Test
    void admin_session_failed_self_patch_keeps_own_session() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "ADMIN");
        String self = AuthHttp.login(port, email, PASSWORD);
        assertThat(patchBySession(self, id, pw("short")).status()).isEqualTo(400);
        assertAlive(self);
    }

    @Test
    void user_password_change_keeps_other_devices_but_later_admin_reset_revokes_all() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "EDITOR");
        String a = AuthHttp.login(port, email, PASSWORD);
        String b = AuthHttp.login(port, email, PASSWORD);
        Resp r = AuthHttp.session(
                port,
                "POST",
                "/api/v1/auth/password",
                a,
                "{\"current_password\":\"" + PASSWORD + "\",\"new_password\":\"" + NEXT + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(204);
        assertAlive(a);
        assertAlive(b);

        assertThat(patchByKey(id, pw(THIRD)).status()).isEqualTo(200);
        assertRevoked(a);
        assertRevoked(b);
    }

    @Test
    void disabling_user_revokes_on_next_request() throws Exception {
        String email = uniqueEmail();
        String id = AuthHttp.createUserId(port, email, "VIEWER");
        String s = AuthHttp.login(port, email, PASSWORD);
        assertThat(patchByKey(id, "{\"status\":\"DISABLED\"}").status()).isEqualTo(200);
        assertRevoked(s);
    }
}
