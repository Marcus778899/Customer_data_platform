package com.cdp.acceptance.query;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cdp.acceptance.engine.EngineTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 追加 1（CDP_TRACE_PLAIN_KEYS 解析規則）驗收測試共用工具。 */
abstract class PlainKeysSupport {

    static final String TENANT = "01JTESTTENANT0000000000001";
    private static final Instant T0 = Instant.parse("2026-10-18T02:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    /** 灌一筆 Enrollment，trigger_snapshot.properties 為 props，回傳 enrollment_id。 */
    String seed(Map<String, Object> props) {
        String jid = EngineTestSupport.ulid();
        String eid = EngineTestSupport.ulid();
        String nodes =
                "[{\"id\":\"start_1\",\"type\":\"StartNode\",\"config\":{},\"outputs\":{\"next\":\"end_1\"}},"
                        + "{\"id\":\"end_1\",\"type\":\"EndNode\",\"config\":{},\"outputs\":{}}]";
        jdbc.update(
                "insert into journey_definition(journey_id, tenant_id, version, name, status, settings,"
                        + " entry_node, nodes, created_at, published_at)"
                        + " values (?,?,1,'j','ACTIVE','{}'::jsonb,'start_1',?::jsonb,?,?)",
                jid,
                TENANT,
                nodes,
                Timestamp.from(T0),
                Timestamp.from(T0));
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("eventId", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        trig.put("eventName", "cart_item_added");
        trig.put("occurredAt", "2026-10-18T01:59:41Z");
        trig.put("sourceType", "CUSTOMER_BEHAVIOR");
        trig.put("properties", props);
        trig.put("profileSnapshot", Map.of());
        trig.put("event_id", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        trig.put("event_name", "cart_item_added");
        trig.put("occurred_at", "2026-10-18T01:59:41Z");
        trig.put("source_type", "CUSTOMER_BEHAVIOR");
        trig.put("profile_snapshot", Map.of());
        jdbc.update(
                "insert into enrollment_record(enrollment_id, tenant_id, journey_id, journey_version,"
                        + " profile_id, status, current_node_id, trigger_snapshot, variables, enrolled_at,"
                        + " updated_at) values (?,?,?,1,?,'ACTIVE','start_1',?::jsonb,'{}'::jsonb,?,?)",
                eid,
                TENANT,
                jid,
                EngineTestSupport.ulid(),
                json.writeValueAsString(trig),
                Timestamp.from(T0),
                Timestamp.from(T0));
        return eid;
    }

    /** 查詳情並回傳 trigger_snapshot.properties。 */
    JsonNode propertiesOf(String eid) throws Exception {
        HttpResponse<String> r =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://localhost:" + port + "/api/v1/enrollments/" + eid))
                                        .header("Authorization", "Bearer test-admin-key")
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new AssertionError("status " + r.statusCode() + " " + r.body());
        }
        return json.readTree(r.body()).get("trigger_snapshot").get("properties");
    }

    static Map<String, Object> sample() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cart_id", "CART-1");
        p.put("safe", "S-1");
        p.put("-", "dash-secret");
        p.put("", "empty-secret");
        p.put("email", "alice@example.com");
        return p;
    }
}
