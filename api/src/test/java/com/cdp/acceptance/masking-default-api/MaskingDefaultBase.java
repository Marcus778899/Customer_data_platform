package com.cdp.acceptance.maskingdefaultapi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.support.TestKeys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * masking-default-api 驗收共用：灌 profile、識別碼與 Enrollment，打真實 HTTP 讀列表與詳情。
 * 套件名不含連字號（Java 不允許），目錄名沿用其他驗收單的做法。
 */
abstract class MaskingDefaultBase {

    private static final Instant T0 = Instant.parse("2026-11-10T02:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    /** 灌 profile（attributes 為 JSON 字串）。 */
    String profile(String attributesJson) {
        String pid = EngineTestSupport.ulid();
        jdbc.update(
                "insert into profile(profile_id, attributes, created_at, updated_at)"
                        + " values (?, ?::jsonb, ?, ?)",
                pid,
                attributesJson,
                Timestamp.from(T0),
                Timestamp.from(T0));
        return pid;
    }

    void identifier(String profileId, String type, String value) {
        jdbc.update(
                "insert into profile_identifier(id_type, id_value, profile_id, created_at)"
                        + " values (?,?,?,?)",
                type,
                value,
                profileId,
                Timestamp.from(T0));
    }

    /** 灌一筆 ACTIVE Enrollment（profile 可不存在），回傳 enrollment_id。 */
    String enrollment(String profileId) {
        String jid = EngineTestSupport.ulid();
        String eid = EngineTestSupport.ulid();
        String nodes =
                "[{\"id\":\"start_1\",\"type\":\"StartNode\",\"config\":{},\"outputs\":{\"next\":\"end_1\"}},"
                        + "{\"id\":\"end_1\",\"type\":\"EndNode\",\"config\":{},\"outputs\":{}}]";
        jdbc.update(
                "insert into journey_definition(journey_id, version, name, status, settings,"
                        + " entry_node, nodes, created_at, published_at)"
                        + " values (?,1,'j','ACTIVE','{}'::jsonb,'start_1',?::jsonb,?,?)",
                jid,
                nodes,
                Timestamp.from(T0),
                Timestamp.from(T0));
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("event_id", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        trig.put("event_name", "cart_item_added");
        trig.put("occurred_at", "2026-11-10T01:59:41Z");
        trig.put("source_type", "CUSTOMER_BEHAVIOR");
        trig.put("properties", Map.of());
        trig.put("profile_snapshot", Map.of());
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version,"
                        + " profile_id, status, current_node_id, trigger_snapshot, variables, enrolled_at,"
                        + " updated_at) values (?,?,1,?,'ACTIVE','start_1',?::jsonb,'{}'::jsonb,?,?)",
                eid,
                jid,
                profileId,
                json.writeValueAsString(trig),
                Timestamp.from(T0),
                Timestamp.from(T0));
        return eid;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                        .header("Authorization", "Bearer " + TestKeys.ADMIN)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new AssertionError("GET " + path + " -> " + r.statusCode() + " " + r.body());
        }
        return json.readTree(r.body());
    }

    /** 以 profile_id 查列表，回傳唯一一筆 EnrollmentSummary。 */
    JsonNode listItem(String profileId) throws Exception {
        JsonNode items =
                get("/api/v1/enrollments?q=" + URLEncoder.encode(profileId, StandardCharsets.UTF_8))
                        .get("items");
        if (items.size() != 1) {
            throw new AssertionError("expected 1 item, got " + items);
        }
        return items.get(0);
    }

    JsonNode detail(String enrollmentId) throws Exception {
        return get("/api/v1/enrollments/" + enrollmentId);
    }
}
