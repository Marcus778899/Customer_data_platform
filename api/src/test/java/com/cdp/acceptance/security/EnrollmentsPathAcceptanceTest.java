package com.cdp.acceptance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** user-auth-api 改寫：session 改為 email 登入的 VIEWER（取代 SUPPORT）。
 * FIX-04 回歸（qa FAIL 1）：enrollments 只放行契約 §4 的三個樣式，其餘子路徑對 SUPPORT 與 INGEST 回 403 §5 格式。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@ActiveProfiles("test")
@Import(SecurityTestSupport.class)
class EnrollmentsPathAcceptanceTest {

    private static final String INGEST = "Bearer test-ingest-key";

    private static final List<String> EXTRA_PATHS = List.of(
            "/api/v1/enrollments/x/foo",
            "/api/v1/enrollments/x/deliveries/y",
            "/api/v1/enrollments/x/deliveries/y/z");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort int port;

    private HttpResponse<String> get(String path, String auth, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (auth != null) b.header("Authorization", auth);
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        return AuthHttp.loginNewUser(port, "VIEWER");
    }

    private void assertForbidden(HttpResponse<String> r, String what) throws Exception {
        assertThat(r.statusCode()).as(what).isEqualTo(403);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).as(what).startsWith("application/problem+json");
        JsonNode n = json.readTree(r.body());
        assertThat(n.path("code").asText()).as(what).isEqualTo("FORBIDDEN");
        assertThat(n.path("request_id").asText()).as(what).isNotBlank();
    }

    @Test
    void extraEnrollmentSubpaths_supportSession_are403() throws Exception {
        String cookie = login();
        for (String p : EXTRA_PATHS) {
            assertForbidden(get(p, null, cookie), "SUPPORT " + p);
        }
    }

    @Test
    void extraEnrollmentSubpaths_ingestKey_are403() throws Exception {
        for (String p : EXTRA_PATHS) {
            assertForbidden(get(p, INGEST, null), "INGEST " + p);
        }
    }

    @Test
    void contractEnrollmentEndpoints_stillAccessibleToSupport() throws Exception {
        String cookie = login();
        for (String p : List.of(
                "/api/v1/enrollments",
                "/api/v1/enrollments/x",
                "/api/v1/enrollments/x/deliveries")) {
            assertThat(get(p, null, cookie).statusCode()).as(p).isNotIn(401, 403);
        }
    }
}
