package com.cdp.acceptance.query;

import static com.cdp.acceptance.engine.EngineTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired JdbcTemplate jdbc;

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

    /** base64url 解碼為「2026-01-01T00:00:00Z|a」+ NUL +「b」；合法 base64，NUL 只在解碼後出現。 */
    private static final String CURSOR_DECODED_NUL = "MjAyNi0wMS0wMVQwMDowMDowMFp8YQBi";

    private String seedEnrollment() {
        String jid = ulid();
        Timestamp now = Timestamp.from(Instant.parse("2026-10-18T02:00:00Z"));
        jdbc.update(
                "insert into journey_definition(journey_id, version, name, status, settings,"
                        + " entry_node, nodes, created_at, published_at)"
                        + " values (?,1,'nul-j','ACTIVE','{}'::jsonb,'start_1','[]'::jsonb,?,?)",
                jid, now, now);
        String eid = ulid();
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version,"
                        + " profile_id, status, current_node_id, trigger_snapshot, variables,"
                        + " enrolled_at, updated_at)"
                        + " values (?,?,1,'nul-profile','WAITING','wait_1','{}'::jsonb,'{}'::jsonb,?,?)",
                eid, jid, now, now);
        return eid;
    }

    @Test
    void cursor_decodedContainsNul_list_returns400OnField_cursor() throws Exception {
        assertValidationFailed(
                get("/api/v1/enrollments?q=x&cursor=" + CURSOR_DECODED_NUL), "cursor");
    }

    @Test
    void cursor_decodedContainsNul_deliveries_returns400OnField_cursor() throws Exception {
        String eid = seedEnrollment();
        assertValidationFailed(
                get("/api/v1/enrollments/" + eid + "/deliveries?cursor=" + CURSOR_DECODED_NUL),
                "cursor");
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
