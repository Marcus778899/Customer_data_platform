package com.cdp.acceptance.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FIX-07 驗收：查詢參數含 NUL（U+0000）回 400 VALIDATION_FAILED，不回 500；一般參數行為不變。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class NulParameterAcceptanceTest {

    private static final String ADMIN = "Bearer test-admin-key";
    private static final String NO_SUCH = "01JNOTEXIST000000000000000";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JsonMapper json;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest r =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", ADMIN)
                        .GET()
                        .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private void assertValidationFailed(HttpResponse<String> r, String fieldName) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(r.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(p.get("request_id").asString())
                .isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
        boolean found = false;
        for (JsonNode e : p.get("errors")) {
            if (fieldName.equals(e.get("field").asString())) found = true;
        }
        assertThat(found).as("errors 應指出欄位 %s：%s", fieldName, r.body()).isTrue();
    }

    @Test
    void q_withNul_returns400ValidationFailedOnField_q() throws Exception {
        for (String q : List.of("x%00y", "%00", "abc%00", "%00abc")) {
            assertValidationFailed(get("/api/v1/enrollments?q=" + q), "q");
        }
    }

    @Test
    void status_withNul_isNot500() throws Exception {
        for (String s : List.of("%00", "WAITING%00", "WAITING,%00")) {
            HttpResponse<String> r = get("/api/v1/enrollments?q=x&status=" + s);
            assertThat(r.statusCode()).as(r.body()).isLessThan(500);
        }
    }

    @Test
    void cursor_withNul_isNot500() throws Exception {
        for (String c : List.of("%00", "abc%00def")) {
            HttpResponse<String> r = get("/api/v1/enrollments?q=x&cursor=" + c);
            assertThat(r.statusCode()).as(r.body()).isLessThan(500);
            r = get("/api/v1/enrollments/" + NO_SUCH + "/deliveries?cursor=" + c);
            assertThat(r.statusCode()).as(r.body()).isLessThan(500);
        }
    }

    @Test
    void detailPath_withNul_stillReturns400() throws Exception {
        assertThat(get("/api/v1/enrollments/x%00y").statusCode()).isEqualTo(400);
    }

    @Test
    void ordinaryQ_unchanged() throws Exception {
        List<String> qs =
                List.of(
                        "plain-customer",
                        "a%27b",
                        "a%25b%5Fc",
                        "%E4%B8%AD%E6%96%87",
                        "a%20b",
                        "a%5Cb",
                        "x".repeat(300));
        for (String q : qs) {
            HttpResponse<String> r = get("/api/v1/enrollments?q=" + q);
            assertThat(r.statusCode()).as("q=%s -> %s", q, r.body()).isEqualTo(200);
            assertThat(json.readTree(r.body()).get("items").isArray()).isTrue();
        }
    }
}
