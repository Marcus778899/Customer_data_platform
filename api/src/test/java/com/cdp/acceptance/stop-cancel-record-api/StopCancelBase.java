package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
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
 * stop-cancel-record-api 驗收共用：真實 HTTP（隨機 port）＋Testcontainers＋可調 Clock。輪詢間隔調成 1 小時，
 * 引擎、relay 只由 runOnce() 驅動。套件名不含連字號（Java 不允許），目錄名沿用派工單。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class StopCancelBase {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected EventDispatcher dispatcher;
    @Autowired protected AdvanceLoop advanceLoop;
    @Autowired protected OutboxRelay relay;
    @Autowired protected ApplicationContext ctx;

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

    protected static Map<String, Object> def(Map<String, Object> settings, List<Map<String, Object>> nodes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "stopcancel-" + uniq());
        d.put("description", null);
        d.put("settings", settings);
        d.put("entry_node", "start");
        d.put("nodes", new ArrayList<>(nodes));
        return d;
    }

    protected static Map<String, Object> start() {
        return node("start", "StartNode", Map.of(), Map.of("next", "src_1"));
    }

    protected static Map<String, Object> src(String event, String next) {
        return node("src_1", "EventSourceNode", Map.of("event_name", event), Map.of("next", next));
    }

    protected static Map<String, Object> end(String id) {
        return node(id, "EndNode", Map.of(), Map.of());
    }

    protected static Map<String, Object> waitDelay(String id, String iso, String next) {
        return node(id, "WaitDelayNode", Map.of("duration", iso), Map.of("next", next));
    }

    protected static Map<String, Object> send(String id, Map<String, Object> outs) {
        return node(id, "SendMessageNode", Map.of("template_id", "tpl_a", "channel_id", "ch_a"), outs);
    }

    /** start, src_1, wait_1(PT1H), send_1, end_1：送出節點後立即走完（F-29：訊息在 outbox 待送、旅程進度已結束）。 */
    protected Map<String, Object> sendThenEnd(Map<String, Object> settings, String event) {
        return def(
                settings,
                List.of(
                        start(),
                        src(event, "wait_1"),
                        waitDelay("wait_1", "PT1H", "send_1"),
                        send("send_1", Map.of("sent", "end_1")),
                        end("end_1")));
    }

    /** start, src_1, wait_1(PT1H), send_1, wait_2(P1D), end_1：訊息排入後仍在等待。 */
    protected Map<String, Object> sendThenWait(Map<String, Object> settings, String event) {
        return def(
                settings,
                List.of(
                        start(),
                        src(event, "wait_1"),
                        waitDelay("wait_1", "PT1H", "send_1"),
                        send("send_1", Map.of("sent", "wait_2")),
                        waitDelay("wait_2", "P1D", "end_1"),
                        end("end_1")));
    }

    // ---------- 使用者與發佈 ----------

    protected record Actor(String cookie) {}

    protected Actor actor(String role) throws Exception {
        String email = AuthHttp.uniqueEmail();
        AuthHttp.createUserId(port, email, role);
        return new Actor(AuthHttp.login(port, email, AuthHttp.PASSWORD));
    }

    protected JsonNode ok(Resp r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.json();
    }

    protected Resp session(Actor a, String method, String path, Object body) throws Exception {
        return AuthHttp.session(port, method, path, a.cookie(), body == null ? null : JSON.writeValueAsString(body));
    }

    /** 草稿 → editor 送審 → admin 核准；回傳 journey_id。 */
    protected String publish(Map<String, Object> definition) throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = ok(session(editor, "POST", "/api/v1/journeys/drafts", Map.of("name", definition.get("name"))), 201)
                .path("journey_id")
                .asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("revision", 1);
        body.put("definition", definition);
        body.put("layout", null);
        int rev = ok(session(editor, "PUT", "/api/v1/journeys/" + id + "/draft", body), 200)
                .path("revision")
                .asInt();
        String rid = ok(session(editor, "POST", "/api/v1/journeys/" + id + "/draft/submit", Map.of("revision", rev)), 201)
                .path("review_id")
                .asString();
        ok(session(admin, "POST", "/api/v1/journeys/" + id + "/reviews/" + rid + "/approve", Map.of()), 200);
        return id;
    }

    protected void stop(String journeyId, String reason) throws Exception {
        Actor editor = actor("EDITOR");
        ok(session(editor, "POST", "/api/v1/journeys/" + journeyId + "/stop", Map.of("reason", reason)), 200);
    }

    /** 跑背景清理（JourneyStopSweeper#runOnce）直到該旅程沒有進行中的旅程進度，最多 20 輪。 */
    protected void sweepUntilDone(String journeyId) throws Exception {
        Object sweeper = ctx.getBean(Class.forName("com.cdp.engine.runtime.JourneyStopSweeper"));
        Method run = sweeper.getClass().getMethod("runOnce");
        for (int i = 0; i < 20; i++) {
            run.invoke(sweeper);
            Integer open = jdbc.queryForObject(
                    "select count(*) from enrollment_record where journey_id = ?"
                            + " and status in ('PENDING','ACTIVE','WAITING','SUSPENDED')",
                    Integer.class,
                    journeyId);
            if (open == 0) return;
        }
    }

    // ---------- 事件與推進 ----------

    protected static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** 只送進攝取 API，回傳 event_id。 */
    protected String ingestOnly(String eventName, String profileId, Map<String, Object> props) throws Exception {
        String eventId = ulid();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", eventId);
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", eventName);
        e.put("occurred_at", clock.instant().toString());
        e.put("identity", Map.of("profile_id", profileId));
        e.put("properties", props);
        Resp r = AuthHttp.bearer(port, "POST", "/api/v1/events", AuthHttp.INGEST_KEY, JSON.writeValueAsString(e));
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        return eventId;
    }

    /** 送事件、派發、推進一輪；回傳 event_id。 */
    protected String ingest(String eventName, String profileId, Map<String, Object> props) throws Exception {
        String id = ingestOnly(eventName, profileId, props);
        dispatcher.runOnce();
        advanceLoop.runOnce();
        return id;
    }

    protected void advance(Duration d) {
        clock.advance(d);
        advanceLoop.runOnce();
    }

    protected int count(String profileId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where profile_id = ?", Integer.class, profileId);
    }

    protected String enrollmentOf(String profileId) {
        assertThat(count(profileId)).as("應有 1 筆旅程進度").isEqualTo(1);
        return jdbc.queryForObject(
                "select enrollment_id from enrollment_record where profile_id = ?", String.class, profileId);
    }

    protected String status(String enrollmentId) {
        return jdbc.queryForObject(
                "select status from enrollment_record where enrollment_id = ?", String.class, enrollmentId);
    }

    protected List<String> outboxStatuses(String enrollmentId) {
        return jdbc.queryForList(
                "select status from effect_outbox where enrollment_id = ? order by 1", String.class, enrollmentId);
    }

    // ---------- 軌跡 API ----------

    protected JsonNode adminGet(String path) throws Exception {
        Resp r = AuthHttp.bearer(port, "GET", path, AuthHttp.ADMIN_KEY, null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    /** GET /enrollments/{id}/deliveries 的 items。 */
    protected JsonNode deliveries(String enrollmentId) throws Exception {
        return adminGet("/api/v1/enrollments/" + enrollmentId + "/deliveries").path("items");
    }

    /** GET /enrollments/{id} 的 delivery_history。 */
    protected JsonNode deliveryHistory(String enrollmentId) throws Exception {
        return adminGet("/api/v1/enrollments/" + enrollmentId).path("delivery_history");
    }

    /** 契約 journey-engine.md §4.1：取消紀錄的欄位。 */
    protected void assertCancelled(JsonNode d, String reason, Instant at) {
        assertThat(d.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(d.path("cancel_reason").asString()).isEqualTo(reason);
        assertThat(Instant.parse(d.path("attempted_at").asString()).truncatedTo(ChronoUnit.MICROS))
                .as("attempted_at = 取消當下的注入時間")
                .isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
        assertThat(d.path("node_id").asString()).isEqualTo("send_1");
        assertThat(d.path("template_id").asString()).isEqualTo("tpl_a");
        assertThat(d.path("channel_id").asString()).isEqualTo("ch_a");
        assertThat(d.path("channel_type").asString()).isNotBlank();
        assertThat(d.path("skip_reason").isNull()).isTrue();
        assertThat(d.path("delivery_id").isNull()).isTrue();
        assertThat(d.path("idempotency_key").asString()).isNotBlank();
    }
}
