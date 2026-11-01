package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;

import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * user-auth-api 改寫（auth.md）：session 改為 email 登入的 VIEWER（取代 SUPPORT），寫入請求自動帶 CSRF 標頭；
 * UNLISTED 移除 {@code GET /api/v1/journeys}（auth.md §2 已列為 VIEWER 以上可讀）。意圖不變。
 *
 * <p>FIX-04 驗收：授權預設拒絕（契約 §1.1：未列於角色表的路徑，有效憑證回 403，未認證回 401）與 session 閒置逾時。
 * 假設：ADMIN 對未知路徑的結果不在此斷言（既有 FrameworkErrorFormatAcceptanceTest 期待 ADMIN 得 404）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class DefaultDenyAcceptanceTest {

    private static final String INGEST = "Bearer test-ingest-key";

    /** 契約 §1.1 沒有列出的 method＋path。 */
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
            new String[] {"PUT", "/api/v1/enrollments/01JXXXXXXXXXXXXXXXXXXXXXXX"},
            new String[] {"DELETE", "/api/v1/enrollments/01JXXXXXXXXXXXXXXXXXXXXXXX"});

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort int port;

    @Autowired ServletWebServerApplicationContext context;

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

    private String login() throws Exception {
        return AuthHttp.loginNewUser(port, "VIEWER");
    }

    private void assertProblem(HttpResponse<String> r, int status, String code, String what) throws Exception {
        assertThat(r.statusCode()).as(what).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).as(what).startsWith("application/problem+json");
        JsonNode n = json.readTree(r.body());
        assertThat(n.path("code").asText()).as(what).isEqualTo(code);
        assertThat(n.path("request_id").asText()).as(what).isNotBlank();
    }

    @Test
    void unlistedPaths_unauthenticated_are401() throws Exception {
        for (String[] p : UNLISTED) {
            assertProblem(call(p[0], p[1], null, null, null), 401, "UNAUTHENTICATED", p[0] + " " + p[1]);
        }
    }

    @Test
    void unlistedPaths_ingestKey_are403() throws Exception {
        for (String[] p : UNLISTED) {
            assertProblem(call(p[0], p[1], INGEST, null, null), 403, "FORBIDDEN", p[0] + " " + p[1]);
        }
    }

    @Test
    void unlistedPaths_viewerSession_are403() throws Exception {
        String cookie = login();
        for (String[] p : UNLISTED) {
            assertProblem(call(p[0], p[1], null, cookie, null), 403, "FORBIDDEN", p[0] + " " + p[1]);
        }
    }

    @Test
    void listedPaths_stillWork() throws Exception {
        String cookie = login();
        assertThat(call("GET", "/api/v1/auth/me", null, cookie, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/enrollments?q=x", null, cookie, null).statusCode())
                .isNotIn(401, 403);
        assertThat(call("POST", "/api/v1/events", INGEST, null, "{}").statusCode()).isNotIn(401, 403);
    }

    // ---- 閒置逾時 ----

    private List<Session> sessions() {
        TomcatWebServer web = (TomcatWebServer) context.getWebServer();
        Context ctx = (Context) web.getTomcat().getHost().findChildren()[0];
        return List.of(ctx.getManager().findSessions());
    }

    @Test
    void idleSession_expires_andMeIs401() throws Exception {
        String cookie = login();
        assertThat(call("GET", "/api/v1/auth/me", null, cookie, null).statusCode()).isEqualTo(200);
        // Tomcat 逾時最小單位為分鐘，無法用設定快轉：把已建立 session 的閒置上限縮成 1 秒，驗證逾時後失效
        List<Session> all = sessions();
        assertThat(all).isNotEmpty();
        all.forEach(s -> s.setMaxInactiveInterval(1));
        Thread.sleep(2500);
        assertProblem(call("GET", "/api/v1/auth/me", null, cookie, null), 401, "UNAUTHENTICATED", "idle");
    }

    @Test
    void newSession_usesConfiguredTimeout() throws Exception {
        login();
        // application-test.yml: server.servlet.session.timeout = PT30M
        assertThat(sessions()).isNotEmpty().allSatisfy(s -> assertThat(s.getMaxInactiveInterval())
                .isEqualTo(1800));
    }
}
