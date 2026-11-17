package com.cdp.acceptance.journeybuilderintegration;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.engine.EngineTestSupport.MutableClock;
import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;
import com.cdp.delivery.relay.OutboxRelay;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * journey-builder-integration 驗收共用：真實 HTTP（隨機 port）＋Testcontainers＋可調 Clock。輪詢間隔調成 1 小時，
 * 引擎、relay 只由 runOnce() 驅動。套件名不含連字號（Java 不允許），目錄名沿用派工單。
 * 金鑰沿用 AuthHttp（develop 尚無 acceptance/support/TestKeys）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class BuilderBase {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected EventDispatcher dispatcher;
    @Autowired protected AdvanceLoop advanceLoop;
    @Autowired protected OutboxRelay relay;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- 定義建構 ----------

    protected static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String ulid() {
        return EngineTestSupport.ulid();
    }

    protected static Map<String, Object> node(
            String id, String type, Map<String, Object> config, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("name", null);
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    protected static Map<String, Object> start() {
        return node("start", "StartNode", Map.of(), Map.of("next", "src_1"));
    }

    protected static Map<String, Object> src(String event, Object filter, String next) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("event_name", event);
        if (filter != null) cfg.put("event_filter", filter);
        return node("src_1", "EventSourceNode", cfg, Map.of("next", next));
    }

    protected static Map<String, Object> end(String id) {
        return node(id, "EndNode", Map.of(), Map.of());
    }

    protected static Map<String, Object> send(String id, Map<String, Object> outs) {
        return node(id, "SendMessageNode", Map.of("template_id", "tpl_a", "channel_id", "ch_a"), outs);
    }

    protected static Map<String, Object> waitDelay(String id, String iso, String next) {
        return node(id, "WaitDelayNode", Map.of("duration", iso), Map.of("next", next));
    }

    protected static Map<String, Object> waitEvent(
            String id, String event, String timeoutIso, Object filter, Map<String, Object> outs) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("event_name", event);
        cfg.put("timeout", timeoutIso);
        if (filter != null) cfg.put("event_filter", filter);
        return node(id, "WaitForEventNode", cfg, outs);
    }

    protected static Map<String, Object> cond(String id, Object condition, String yes, String no) {
        return node(id, "ConditionNode", Map.of("condition", condition), Map.of("yes", yes, "no", no));
    }

    protected static Map<String, Object> leaf(String field, String operator, Object value) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("type", "attribute");
        l.put("field", field);
        l.put("operator", operator);
        if (value != null) l.put("value", value);
        return l;
    }

    protected static Map<String, Object> group(String op, Object... children) {
        return Map.of("op", op, "children", List.of(children));
    }

    protected static Map<String, Object> def(Map<String, Object> settings, List<Map<String, Object>> nodes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "builder-" + uniq());
        d.put("description", null);
        d.put("settings", settings);
        d.put("entry_node", "start");
        d.put("nodes", new ArrayList<>(nodes));
        return d;
    }

    // ---------- 整合單新增 ----------

    protected Resp as(Actor a, String method, String path, Object body) throws Exception {
        return AuthHttp.session(port, method, path, a.cookie(), body == null ? null : JSON.writeValueAsString(body));
    }

    /** 對既有旅程發新版（revision 0 以最新版本為底；EDITOR 送審、另一位 ADMIN 核准）；回傳新版本號。 */
    protected int publishNext(String journeyId, Map<String, Object> definition) throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", 0);
        body.put("definition", definition);
        body.put("layout", null);
        int rev = okJson(as(editor, "PUT", "/api/v1/journeys/" + journeyId + "/draft", body), 200)
                .path("revision")
                .asInt();
        String rid = okJson(
                        as(editor, "POST", "/api/v1/journeys/" + journeyId + "/draft/submit", Map.of("revision", rev)),
                        201)
                .path("review_id")
                .asString();
        return okJson(as(admin, "POST", "/api/v1/journeys/" + journeyId + "/reviews/" + rid + "/approve", Map.of()), 200)
                .path("version")
                .asInt();
    }

    /** relay 推一輪後等 Mock 通道收到 n 則（逾時 15 秒）；回傳實際則數。 */
    protected int awaitMock(String enrollmentId, int n) throws Exception {
        relay.runOnce();
        long deadline = System.currentTimeMillis() + 15_000;
        int sent = 0;
        while (System.currentTimeMillis() < deadline) {
            sent = mockCount(enrollmentId);
            if (sent >= n) break;
            Thread.sleep(200);
        }
        return sent;
    }

    protected int mockCount(String enrollmentId) {
        return jdbc.queryForObject(
                "select count(*) from mock_channel_message where enrollment_id = ?", Integer.class, enrollmentId);
    }

    protected int versionOfEnrollment(String enrollmentId) {
        return jdbc.queryForObject(
                "select journey_version from enrollment_record where enrollment_id = ?", Integer.class, enrollmentId);
    }

    protected JsonNode statsNode(String journeyId, int version, String nodeId) throws Exception {
        JsonNode stats = apiGet("/api/v1/journeys/" + journeyId + "/versions/" + version + "/node-stats");
        for (JsonNode it : stats.path("items")) if (it.path("node_id").asString().equals(nodeId)) return it;
        return null;
    }

    /** 登入中的使用者。 */
    protected record Actor(String cookie) {}

    protected Actor actor(String role) throws Exception {
        String email = AuthHttp.uniqueEmail();
        AuthHttp.createUserId(port, email, role);
        return new Actor(AuthHttp.login(port, email, AuthHttp.PASSWORD));
    }

    protected JsonNode okJson(Resp r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.json();
    }

    /**
     * 走草稿與審核流程發佈（EDITOR 存草稿送審、ADMIN 核准），回傳 journey_id。POST /journeys 目前只收 v0.1.0 五種節點，
     * 分支節點只能經草稿流程發佈。
     */
    protected String publish(Map<String, Object> definition) throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = okJson(
                        AuthHttp.session(
                                port,
                                "POST",
                                "/api/v1/journeys/drafts",
                                editor.cookie(),
                                JSON.writeValueAsString(Map.of("name", definition.get("name")))),
                        201)
                .path("journey_id")
                .asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", 1);
        body.put("definition", definition);
        body.put("layout", null);
        int rev = okJson(
                        AuthHttp.session(
                                port,
                                "PUT",
                                "/api/v1/journeys/" + id + "/draft",
                                editor.cookie(),
                                JSON.writeValueAsString(body)),
                        200)
                .path("revision")
                .asInt();
        String reviewId = okJson(
                        AuthHttp.session(
                                port,
                                "POST",
                                "/api/v1/journeys/" + id + "/draft/submit",
                                editor.cookie(),
                                JSON.writeValueAsString(Map.of("revision", rev))),
                        201)
                .path("review_id")
                .asString();
        okJson(
                AuthHttp.session(
                        port,
                        "POST",
                        "/api/v1/journeys/" + id + "/reviews/" + reviewId + "/approve",
                        admin.cookie(),
                        "{}"),
                200);
        return id;
    }

    // ---------- 事件與推進 ----------

    protected static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** occurred_at = 目前模擬時間。 */
    protected void ingest(String eventName, String profileId, Map<String, Object> props) throws Exception {
        ingestAt(eventName, profileId, props, clock.instant());
    }

    protected void ingestAt(String eventName, String profileId, Map<String, Object> props, Instant occurredAt)
            throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", ulid());
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", eventName);
        e.put("occurred_at", occurredAt.toString());
        e.put("identity", Map.of("profile_id", profileId));
        if (props != null) e.put("properties", props);
        Resp r = AuthHttp.bearer(port, "POST", "/api/v1/events", INGEST_KEY, JSON.writeValueAsString(e));
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        dispatcher.runOnce();
        advanceLoop.runOnce();
    }

    protected void advance(Duration d) {
        clock.advance(d);
        advanceLoop.runOnce();
    }

    protected void putProfile(String profileId, Map<String, Object> attributes) throws Exception {
        Resp r = AuthHttp.bearer(
                port,
                "PUT",
                "/api/v1/profiles/" + profileId,
                INGEST_KEY,
                JSON.writeValueAsString(Map.of("attributes", attributes)));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    // ---------- 觀察 ----------

    protected int enrollmentCount(String profileId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where profile_id = ?", Integer.class, profileId);
    }

    protected String enrollmentOf(String profileId) {
        assertThat(enrollmentCount(profileId)).as("應有 1 筆旅程進度").isEqualTo(1);
        return jdbc.queryForObject(
                "select enrollment_id from enrollment_record where profile_id = ?", String.class, profileId);
    }

    protected String status(String enrollmentId) {
        return jdbc.queryForObject(
                "select status from enrollment_record where enrollment_id = ?", String.class, enrollmentId);
    }

    protected String terminationReason(String enrollmentId) {
        return jdbc.queryForObject(
                "select termination_reason from enrollment_record where enrollment_id = ?",
                String.class,
                enrollmentId);
    }

    protected boolean terminated(String enrollmentId) {
        return jdbc.queryForObject(
                "select terminated_at is not null from enrollment_record where enrollment_id = ?",
                Boolean.class,
                enrollmentId);
    }

    protected int visits(String enrollmentId, String nodeId) {
        return jdbc.queryForObject(
                "select count(*) from node_trace where enrollment_id = ? and node_id = ?",
                Integer.class,
                enrollmentId,
                nodeId);
    }

    /** 該節點最後一次離開的出口；沒有離開過 = null。 */
    protected String lastPort(String enrollmentId, String nodeId) {
        List<String> r = jdbc.queryForList(
                "select out_port from node_trace where enrollment_id = ? and node_id = ? and exited_at is not null"
                        + " order by seq desc limit 1",
                String.class,
                enrollmentId,
                nodeId);
        return r.isEmpty() ? null : r.get(0);
    }

    protected List<String> outboxStatuses(String enrollmentId) {
        return jdbc.queryForList(
                "select status from effect_outbox where enrollment_id = ? order by 1", String.class, enrollmentId);
    }

    protected JsonNode apiGet(String path) throws Exception {
        Resp r = AuthHttp.bearer(port, "GET", path, ADMIN_KEY, null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }
}
