package com.cdp.acceptance.journeynodestatsapi;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** journey-node-stats-api 驗收：journeys.md 第 5 節節點統計（欄位、範圍、權限、錯誤、逾時）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class NodeStatsAcceptanceTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final Instant T = EngineTestSupport.BASE;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private static Map<String, Object> node(String id, String type, Map<String, Object> cfg, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("config", cfg);
        n.put("outputs", outs);
        return n;
    }

    private String publish() throws Exception {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "stats-" + UUID.randomUUID());
        j.put("settings", Map.of());
        j.put("entry_node", "start_1");
        j.put(
                "nodes",
                List.of(
                        node("start_1", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", "evt_ns_" + UUID.randomUUID()), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", "P3D"), Map.of("next", "send_1")),
                        node("send_1", "SendMessageNode", Map.of("template_id", "tpl_a", "channel_id", "ch_a"), Map.of("sent", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        Resp r = AuthHttp.bearer(port, "POST", "/api/v1/journeys", ADMIN_KEY, JSON.writeValueAsString(j));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("journey_id").asString();
    }

    private String enroll(String journeyId, int version, String status, String currentNode) {
        String id = EngineTestSupport.ulid();
        boolean terminal = !List.of("WAITING", "ACTIVE", "PENDING", "SUSPENDED").contains(status);
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                        + " current_node_id, trigger_snapshot, variables, enrolled_at, updated_at, terminated_at)"
                        + " values (?,?,?,?,?,?,'{}'::jsonb,'{}'::jsonb,?,?,?)",
                id,
                journeyId,
                version,
                "p-" + UUID.randomUUID(),
                status,
                currentNode,
                Timestamp.from(T),
                Timestamp.from(T),
                terminal ? Timestamp.from(T) : null);
        return id;
    }

    private void trace(String enrollmentId, long seq, String nodeId, String outPort) {
        jdbc.update(
                "insert into node_trace(enrollment_id, seq, node_id, node_type, entered_at, exited_at, out_port,"
                        + " decision_reason, evaluated) values (?,?,?,?,?,?,?,?,'{}'::jsonb)",
                enrollmentId,
                seq,
                nodeId,
                "X",
                Timestamp.from(T),
                outPort == null ? null : Timestamp.from(T),
                outPort,
                "r");
    }

    private Resp stats(String journeyId, String version) throws Exception {
        return AuthHttp.bearer(
                port, "GET", "/api/v1/journeys/" + journeyId + "/versions/" + version + "/node-stats", ADMIN_KEY, null);
    }

    private JsonNode statsOk(String journeyId, int version) throws Exception {
        Resp r = stats(journeyId, String.valueOf(version));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    private static JsonNode item(JsonNode stats, String nodeId) {
        for (JsonNode n : stats.path("items")) if (n.path("node_id").asString().equals(nodeId)) return n;
        throw new AssertionError("no item " + nodeId + " in " + stats);
    }

    private static void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.json().path("code").asString()).isEqualTo(code);
    }

    // ---------- 欄位 ----------

    @Test
    void emptyVersion_listsEveryNodeExceptStart_inNodesOrder_allZero() throws Exception {
        String j = publish();
        JsonNode s = statsOk(j, 1);
        assertThat(s.path("journey_id").asString()).isEqualTo(j);
        assertThat(s.path("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(s.path("as_of").asString())).isNotNull();
        List<String> ids = new ArrayList<>();
        for (JsonNode n : s.path("items")) ids.add(n.path("node_id").asString());
        assertThat(ids).containsExactly("src_1", "wait_1", "send_1", "end_1");
        for (JsonNode n : s.path("items")) {
            assertThat(n.path("entered").asInt()).isZero();
            assertThat(n.path("visits").asInt()).isZero();
            assertThat(n.path("current").asInt()).isZero();
            assertThat(n.path("out_ports").isObject()).isTrue();
            assertThat(n.path("out_ports")).isEmpty();
        }
    }

    @Test
    void counts_enteredVisitsCurrentOutPorts() throws Exception {
        String j = publish();
        String e1 = enroll(j, 1, "COMPLETED", "end_1");
        trace(e1, 1, "src_1", "next");
        trace(e1, 2, "wait_1", "next");
        trace(e1, 3, "send_1", "sent");
        trace(e1, 4, "end_1", null);
        String e2 = enroll(j, 1, "WAITING", "wait_1");
        trace(e2, 1, "src_1", "next");
        trace(e2, 2, "wait_1", null);

        JsonNode s = statsOk(j, 1);
        JsonNode src = item(s, "src_1");
        assertThat(src.path("entered").asInt()).isEqualTo(2);
        assertThat(src.path("visits").asInt()).isEqualTo(2);
        assertThat(src.path("current").asInt()).isZero();
        assertThat(src.path("out_ports").path("next").asInt()).isEqualTo(2);

        JsonNode wait = item(s, "wait_1");
        assertThat(wait.path("entered").asInt()).isEqualTo(2);
        assertThat(wait.path("visits").asInt()).isEqualTo(2);
        assertThat(wait.path("current").asInt()).isEqualTo(1);
        assertThat(wait.path("out_ports").path("next").asInt()).isEqualTo(1);
        assertThat(wait.path("out_ports")).hasSize(1);

        JsonNode send = item(s, "send_1");
        assertThat(send.path("entered").asInt()).isEqualTo(1);
        assertThat(send.path("out_ports").path("sent").asInt()).isEqualTo(1);

        JsonNode end = item(s, "end_1");
        assertThat(end.path("entered").asInt()).isEqualTo(1);
        assertThat(end.path("current").asInt()).as("已結束不算 current").isZero();
        assertThat(end.path("out_ports")).isEmpty();
    }

    @Test
    void loop_enteredCountsPeopleOnce_visitsCountsRows() throws Exception {
        String j = publish();
        String e = enroll(j, 1, "WAITING", "wait_1");
        trace(e, 1, "src_1", "next");
        trace(e, 2, "wait_1", "again");
        trace(e, 3, "wait_1", "again");
        trace(e, 4, "wait_1", null);
        String other = enroll(j, 1, "WAITING", "wait_1");
        trace(other, 1, "wait_1", null);

        JsonNode wait = item(statsOk(j, 1), "wait_1");
        assertThat(wait.path("entered").asInt()).as("兩人").isEqualTo(2);
        assertThat(wait.path("visits").asInt()).as("四列").isEqualTo(4);
        assertThat(wait.path("current").asInt()).isEqualTo(2);
        assertThat(wait.path("out_ports").path("again").asInt()).isEqualTo(2);
        assertThat(wait.path("out_ports")).hasSize(1);
    }

    @Test
    void midwayTerminated_countedInLastNodeEntered_notCurrent() throws Exception {
        String j = publish();
        for (String st : List.of("ABORTED", "GOAL_MET", "EXITED")) {
            String e = enroll(j, 1, st, "wait_1");
            trace(e, 1, "src_1", "next");
            trace(e, 2, "wait_1", null);
        }
        JsonNode wait = item(statsOk(j, 1), "wait_1");
        assertThat(wait.path("entered").asInt()).isEqualTo(3);
        assertThat(wait.path("current").asInt()).isZero();
    }

    @Test
    void scopedToRequestedVersion_includingFinished() throws Exception {
        String j = publish();
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("journey_id", j);
        v2.put("name", "stats-v2-" + UUID.randomUUID());
        v2.put("settings", Map.of());
        v2.put("entry_node", "start_1");
        v2.put(
                "nodes",
                List.of(
                        node("start_1", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", "evt_ns_" + UUID.randomUUID()), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        Resp pr = AuthHttp.bearer(port, "POST", "/api/v1/journeys", ADMIN_KEY, JSON.writeValueAsString(v2));
        assertThat(pr.status()).as(pr.body()).isEqualTo(201);

        String a = enroll(j, 1, "COMPLETED", "end_1");
        trace(a, 1, "src_1", "next");
        String b = enroll(j, 2, "WAITING", "src_1");
        trace(b, 1, "src_1", null);
        String c = enroll(j, 2, "COMPLETED", "end_1");
        trace(c, 1, "src_1", "next");
        trace(c, 2, "end_1", null);

        JsonNode s1 = statsOk(j, 1);
        assertThat(item(s1, "src_1").path("entered").asInt()).isEqualTo(1);
        JsonNode s2 = statsOk(j, 2);
        assertThat(s2.path("version").asInt()).isEqualTo(2);
        assertThat(item(s2, "src_1").path("entered").asInt()).isEqualTo(2);
        assertThat(item(s2, "src_1").path("current").asInt()).isEqualTo(1);
        assertThat(item(s2, "end_1").path("entered").asInt()).isEqualTo(1);
        assertThat(s2.path("items")).hasSize(2);
    }

    @Test
    void notCached_reflectsNewDataOnNextCall() throws Exception {
        String j = publish();
        assertThat(item(statsOk(j, 1), "src_1").path("entered").asInt()).isZero();
        String e = enroll(j, 1, "WAITING", "src_1");
        trace(e, 1, "src_1", null);
        assertThat(item(statsOk(j, 1), "src_1").path("entered").asInt()).isEqualTo(1);
    }

    // ---------- 權限與錯誤 ----------

    @Test
    void viewerSession_canRead() throws Exception {
        String j = publish();
        String cookie = AuthHttp.loginNewUser(port, "VIEWER");
        Resp r = AuthHttp.session(port, "GET", "/api/v1/journeys/" + j + "/versions/1/node-stats", cookie, null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    @Test
    void unauthenticated_is401_ingestKey_is403_andAuthBeforeValidation() throws Exception {
        String j = publish();
        String path = "/api/v1/journeys/" + j + "/versions/1/node-stats";
        assertProblem(AuthHttp.send(port, "GET", path, null, null, null), 401, "UNAUTHENTICATED");
        assertProblem(AuthHttp.bearer(port, "GET", path, INGEST_KEY, null), 403, "FORBIDDEN");
        assertProblem(
                AuthHttp.send(port, "GET", "/api/v1/journeys/x/versions/abc/node-stats", null, null, null),
                401,
                "UNAUTHENTICATED");
    }

    @Test
    void unknownJourneyOrVersion_is404() throws Exception {
        String j = publish();
        assertProblem(stats(j, "99"), 404, "JOURNEY_NOT_FOUND");
        assertProblem(stats(EngineTestSupport.ulid(), "1"), 404, "JOURNEY_NOT_FOUND");
    }

    @Test
    void invalidVersion_is400() throws Exception {
        String j = publish();
        for (String v : List.of("abc", "0", "-1", "1.5")) {
            assertProblem(stats(j, v), 400, "VALIDATION_FAILED");
        }
    }

    /** 契約未列專用逾時碼；只能落在 journey-engine 第 1 節錯誤表的 5xx（INTERNAL_ERROR 或 SERVICE_UNAVAILABLE）。 */
    @Test
    void queryOverFiveSeconds_returnsServerErrorPerContract_andDoesNotHang() throws Exception {
        String j = publish();
        String e = enroll(j, 1, "WAITING", "src_1");
        trace(e, 1, "src_1", null);
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                c.createStatement().execute("LOCK TABLE node_trace IN ACCESS EXCLUSIVE MODE");
                long t0 = System.nanoTime();
                Resp r = stats(j, "1");
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(ms).as("約 5 秒後放棄").isBetween(4_000L, 20_000L);
                assertThat(r.status()).as(r.body()).isIn(500, 503);
                assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
                assertThat(r.json().path("code").asString()).isIn("INTERNAL_ERROR", "SERVICE_UNAVAILABLE");
                assertThat(r.body()).as("5xx 不含內部訊息").doesNotContain("statement timeout").doesNotContain("PSQL");
            } finally {
                c.rollback();
            }
        }
        assertThat(stats(j, "1").status()).isEqualTo(200);
    }
}
