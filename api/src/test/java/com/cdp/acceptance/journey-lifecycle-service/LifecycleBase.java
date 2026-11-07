package com.cdp.acceptance.journeylifecycleservice;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

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
 * journey-lifecycle-service 驗收共用：真實 HTTP（隨機 port）＋Testcontainers＋可調 Clock。輪詢間隔調成 1 小時，
 * 引擎、relay 只由 runOnce() 驅動。套件名不含連字號（Java 不允許），目錄名沿用派工單。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class LifecycleBase {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected EventDispatcher dispatcher;
    @Autowired protected AdvanceLoop advanceLoop;
    @Autowired protected OutboxRelay relay;
    @Autowired protected ApplicationContext ctx;

    /** 登入中的使用者。 */
    protected record Actor(String cookie, String userId, String displayName) {}

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- 使用者 ----------

    protected Actor actor(String role) throws Exception {
        String email = AuthHttp.uniqueEmail();
        String id = AuthHttp.createUserId(port, email, role);
        String cookie = AuthHttp.login(port, email, AuthHttp.PASSWORD);
        return new Actor(cookie, id, "QA " + role);
    }

    // ---------- HTTP ----------

    protected Resp as(Actor a, String method, String path, Object body) throws Exception {
        return AuthHttp.session(port, method, path, a.cookie(), body == null ? null : js(body));
    }

    protected Resp key(String method, String path, Object body) throws Exception {
        return AuthHttp.bearer(port, method, path, ADMIN_KEY, body == null ? null : js(body));
    }

    protected JsonNode ok(Resp r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        return r.json();
    }

    protected void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.json().path("code").asString()).isEqualTo(code);
    }

    protected JsonNode getDetail(String journeyId) throws Exception {
        return ok(key("GET", "/api/v1/journeys/" + journeyId, null), 200);
    }

    protected JsonNode reviews(String journeyId) throws Exception {
        return ok(key("GET", "/api/v1/journeys/" + journeyId + "/reviews", null), 200).path("items");
    }

    protected static String js(Object o) {
        return o instanceof String s ? s : JSON.writeValueAsString(o);
    }

    protected static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    protected static String ulid() {
        return EngineTestSupport.ulid();
    }

    protected static List<String> field(JsonNode arr, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.path(name).asString());
        return out;
    }

    // ---------- 定義建構 ----------

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

    protected static Map<String, Object> def(String name, List<Map<String, Object>> nodes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("description", null);
        d.put("settings", Map.of());
        d.put("entry_node", "start");
        d.put("nodes", new ArrayList<>(nodes));
        return d;
    }

    /** start, src_1, wait_1, send_1, end_1（可通過驗證）。 */
    protected static Map<String, Object> sendDef(String name, String eventName, String waitIso) {
        return def(
                name,
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", eventName), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", waitIso), Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
    }

    /** start, src_1, wait_1, end_1（沒有訊息）。 */
    protected static Map<String, Object> plainDef(String name, String eventName, String waitIso) {
        return def(
                name,
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", eventName), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", waitIso), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
    }

    /** start, src_1, wait_1(PT1H), send_1, wait_2(P1D), end_1。 */
    protected static Map<String, Object> sendThenWaitDef(String name, String eventName) {
        return def(
                name,
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", eventName), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "wait_2")),
                        node("wait_2", "WaitDelayNode", Map.of("duration", "P1D"), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
    }

    // ---------- 生命週期流程 ----------

    protected String newDraft(Actor a, String name) throws Exception {
        JsonNode r = ok(as(a, "POST", "/api/v1/journeys/drafts", Map.of("name", name)), 201);
        return r.path("journey_id").asString();
    }

    protected Resp saveRaw(Actor a, String journeyId, int revision, Object definition) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", revision);
        b.put("definition", definition);
        b.put("layout", null);
        return as(a, "PUT", "/api/v1/journeys/" + journeyId + "/draft", b);
    }

    /** PUT 草稿，回傳新 revision。 */
    protected int save(Actor a, String journeyId, int revision, Map<String, Object> definition) throws Exception {
        return ok(saveRaw(a, journeyId, revision, definition), 200).path("revision").asInt();
    }

    protected Resp submitRaw(Actor a, String journeyId, int revision, String note) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", revision);
        if (note != null) b.put("note", note);
        return as(a, "POST", "/api/v1/journeys/" + journeyId + "/draft/submit", b);
    }

    protected String submit(Actor a, String journeyId, int revision) throws Exception {
        return ok(submitRaw(a, journeyId, revision, null), 201).path("review_id").asString();
    }

    protected Resp reviewAction(Actor a, String journeyId, String reviewId, String action, Object body)
            throws Exception {
        return as(a, "POST", "/api/v1/journeys/" + journeyId + "/reviews/" + reviewId + "/" + action, body);
    }

    /** 建草稿、存定義、送審、他人核准；回傳 journey_id。 */
    protected String publishNew(Actor editor, Actor approver, Map<String, Object> definition) throws Exception {
        String id = newDraft(editor, (String) definition.get("name"));
        int rev = save(editor, id, 1, definition);
        String rid = submit(editor, id, rev);
        ok(reviewAction(approver, id, rid, "approve", Map.of()), 200);
        return id;
    }

    /** 對既有旅程發新版（revision 0 以最新版本為底）；回傳新版本號。 */
    protected int publishNext(Actor editor, Actor approver, String journeyId, Map<String, Object> definition)
            throws Exception {
        int rev = save(editor, journeyId, 0, definition);
        String rid = submit(editor, journeyId, rev);
        return ok(reviewAction(approver, journeyId, rid, "approve", Map.of()), 200).path("version").asInt();
    }

    // ---------- 事件 ----------

    protected String ingest(String eventName, String profileId, Map<String, Object> props) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", ulid());
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", eventName);
        e.put("occurred_at", clock.instant().minusSeconds(60).toString());
        e.put("identity", Map.of("profile_id", profileId));
        e.put("properties", props);
        Resp r = AuthHttp.bearer(port, "POST", "/api/v1/events", INGEST_KEY, js(e));
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        dispatcher.runOnce();
        return profileId;
    }

    protected String enrollmentOf(String profileId) {
        return jdbc.queryForObject(
                "select enrollment_id from enrollment_record where profile_id = ?", String.class, profileId);
    }

    protected Instant instant(JsonNode n) {
        return Instant.parse(n.asString());
    }
}
