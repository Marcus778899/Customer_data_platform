package com.cdp.acceptance.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.engine.EngineTestSupport.MutableClock;
import com.cdp.acceptance.security.AuthHttp;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BE-03 驗收：契約 §1.1、§4（含 §4.4 遮罩）、§5。經真實 HTTP 呼叫；資料以 SQL 灌入（delivery_task 等 BE-02 才會寫的表只能灌表），
 * 另有一條走真實寫入路徑（建旅程、攝取、runOnce）。明文白名單以 cdp.trace.plain-keys 覆寫為 cart_id,cart_total,safe_tree。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys=cart_id,cart_total,safe_tree"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class EnrollmentQueryAcceptanceTest {

    private static final String ADMIN = "Bearer test-admin-key";
    private static final String INGEST = "Bearer test-ingest-key";
    private static final Instant T0 = Instant.parse("2026-10-18T02:00:00Z");
    private static final String NO_SUCH = "01JNOTEXIST000000000000000";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;
    @Autowired EventDispatcher dispatcher;
    @Autowired AdvanceLoop advanceLoop;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- HTTP helpers ----------

    private HttpResponse<String> call(
            String method, String path, String auth, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (auth != null) b.header("Authorization", auth);
        if (cookie != null) b.header("Cookie", cookie);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return call("GET", path, ADMIN, null, null);
    }

    private JsonNode getOk(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode()).as("GET %s -> %s", path, r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private String sessionCookie() throws Exception {
        // user-auth-api 改寫：email 登入的 VIEWER（帳號經管理金鑰建立）取代 v0.1.0 客服帳密
        return AuthHttp.loginNewUser(port, "VIEWER");
    }

    private void assertProblem(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode p = json.readTree(r.body());
        assertThat(p.get("code").asString()).isEqualTo(code);
        assertThat(p.get("status").asInt()).isEqualTo(status);
        assertThat(p.get("type").asString()).isEqualTo("about:blank");
        assertThat(p.get("request_id").asString())
                .isEqualTo(r.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Instant t(JsonNode n) {
        return Instant.parse(n.asString());
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asString());
        return out;
    }

    private static List<String> field(JsonNode arr, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.get(name).asString());
        return out;
    }

    // ---------- seeding helpers ----------

    private static String ulid() {
        return EngineTestSupport.ulid();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private String js(Object o) {
        return json.writeValueAsString(o);
    }

    private static Map<String, Object> node(
            String id,
            String type,
            String name,
            Map<String, Object> config,
            Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        if (name != null) n.put("name", name);
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    private static List<Map<String, Object>> nodes(String namePrefix) {
        return List.of(
                node("start_1", "StartNode", null, Map.of(), Map.of("next", "src_1")),
                node(
                        "src_1",
                        "EventSourceNode",
                        namePrefix + "-來源",
                        Map.of("event_name", "evt_x"),
                        Map.of("next", "wait_1")),
                node(
                        "wait_1",
                        "WaitDelayNode",
                        namePrefix + "-等待",
                        Map.of("duration", "P3D"),
                        Map.of("next", "send_1")),
                node(
                        "send_1",
                        "SendMessageNode",
                        null,
                        Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                        Map.of("sent", "end_1")),
                node("end_1", "EndNode", null, Map.of(), Map.of()));
    }

    private void seedJourney(String journeyId, int version, String name, String namePrefix) {
        jdbc.update(
                "insert into journey_definition(journey_id, version, name, status, settings,"
                        + " entry_node, nodes, created_at, published_at)"
                        + " values (?,?,?,'ACTIVE','{}'::jsonb,'start_1',?::jsonb,?,?)",
                journeyId,
                version,
                name,
                js(nodes(namePrefix)),
                ts(T0),
                ts(T0));
    }

    private String newJourney(String name) {
        String jid = ulid();
        seedJourney(jid, 1, name, "v1");
        return jid;
    }

    /** 可調欄位的 Enrollment 種子。 */
    private final class Enr {
        String id = ulid();
        final String journeyId;
        int version = 1;
        final String profile;
        String status = "WAITING";
        String node = "wait_1";
        Instant enrolledAt = T0;
        Instant updatedAt = T0;
        Instant terminatedAt;
        String terminationReason;
        String wakeType;
        Instant wakeAt;
        Map<String, Object> variables = Map.of();
        Map<String, Object> trigger = buildTrigger(Map.of("cart_id", "CART-1"), Map.of());

        Enr(String journeyId, String profile) {
            this.journeyId = journeyId;
            this.profile = profile;
        }

        Enr status(String s) {
            status = s;
            return this;
        }

        Enr at(Instant i) {
            enrolledAt = i;
            updatedAt = i;
            return this;
        }

        Enr version(int v) {
            version = v;
            return this;
        }

        Enr node(String n) {
            node = n;
            return this;
        }

        Enr timer(Instant at) {
            wakeType = "TIMER";
            wakeAt = at;
            return this;
        }

        Enr terminated(Instant at, String reason) {
            terminatedAt = at;
            terminationReason = reason;
            return this;
        }

        Enr variables(Map<String, Object> v) {
            variables = v;
            return this;
        }

        Enr withTrigger(Map<String, Object> props, Map<String, Object> profileSnapshot) {
            trigger = buildTrigger(props, profileSnapshot);
            return this;
        }

        String save() {
            jdbc.update(
                    "insert into enrollment_record(enrollment_id, journey_id, journey_version,"
                            + " profile_id, status, current_node_id, wake_type, next_wake_at, trigger_snapshot,"
                            + " variables, enrolled_at, updated_at, terminated_at, termination_reason)"
                            + " values (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)",
                    id,
                    journeyId,
                    version,
                    profile,
                    status,
                    node,
                    wakeType,
                    ts(wakeAt),
                    js(trigger),
                    js(variables),
                    ts(enrolledAt),
                    ts(updatedAt),
                    ts(terminatedAt),
                    terminationReason);
            return id;
        }
    }

    /** trigger_snapshot 持久化格式屬引擎內部；同時寫 camelCase 與 snake_case 鍵，讓兩種實作都能還原。 */
    private static Map<String, Object> buildTrigger(
            Map<String, Object> props, Map<String, Object> profileSnapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        m.put("eventName", "cart_item_added");
        m.put("occurredAt", "2026-10-18T01:59:41Z");
        m.put("sourceType", "CUSTOMER_BEHAVIOR");
        m.put("properties", props);
        m.put("profileSnapshot", profileSnapshot);
        m.put("event_id", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        m.put("event_name", "cart_item_added");
        m.put("occurred_at", "2026-10-18T01:59:41Z");
        m.put("source_type", "CUSTOMER_BEHAVIOR");
        m.put("profile_snapshot", profileSnapshot);
        return m;
    }

    private void seedTrace(
            String eid,
            long seq,
            String nodeId,
            String nodeType,
            Instant entered,
            Instant exited,
            String outPort,
            String reason,
            Map<String, Object> evaluated) {
        jdbc.update(
                "insert into node_trace(enrollment_id, seq, node_id, node_type, entered_at,"
                        + " exited_at, out_port, decision_reason, evaluated)"
                        + " values (?,?,?,?,?,?,?,?,?::jsonb)",
                eid,
                seq,
                nodeId,
                nodeType,
                ts(entered),
                ts(exited),
                outPort,
                reason,
                js(evaluated));
    }

    private void seedDelivery(
            String eid,
            String key,
            String nodeId,
            long visitSeq,
            String status,
            String skipReason,
            String deliveryId,
            Instant attemptedAt) {
        jdbc.update(
                "insert into delivery_task(idempotency_key, enrollment_id, journey_id,"
                        + " journey_version, node_id, visit_seq, template_id, channel_id, channel_type,"
                        + " message_type, status, skip_reason, delivery_id, attempted_at, created_at,"
                        + " updated_at) values (?,?,'j',1,?,?,'tpl_a','ch_a','MOCK',null,?,?,?,?,?,?)",
                key,
                eid,
                nodeId,
                visitSeq,
                status,
                skipReason,
                deliveryId,
                ts(attemptedAt),
                ts(attemptedAt),
                ts(attemptedAt));
    }

    // ---------- §4 列表 ----------

    @Test
    void list_byProfileId_returnsSummaryFields_newestFirst_withBoundVersionNames() throws Exception {
        String jid = ulid();
        seedJourney(jid, 1, "旅程舊名", "舊");
        seedJourney(jid, 2, "旅程新名", "新");
        String profile = ulid();
        String older = new Enr(jid, profile).at(T0).version(1).save();
        String newer =
                new Enr(jid, profile)
                        .at(T0.plusSeconds(60))
                        .version(2)
                        .status("COMPLETED")
                        .node("send_1")
                        .terminated(T0.plusSeconds(90), "完成")
                        .save();

        JsonNode page = getOk("/api/v1/enrollments?q=" + profile);
        JsonNode items = page.get("items");
        assertThat(field(items, "enrollment_id")).containsExactly(newer, older);
        assertThat(page.get("next_cursor").isNull()).isTrue();

        JsonNode second = items.get(1);
        assertThat(second.get("journey_id").asString()).isEqualTo(jid);
        assertThat(second.get("journey_version").asInt()).isEqualTo(1);
        assertThat(second.get("journey_name").asString()).isEqualTo("旅程舊名");
        assertThat(second.get("profile_id").asString()).isEqualTo(profile);
        assertThat(second.get("status").asString()).isEqualTo("WAITING");
        assertThat(second.get("current_node_id").asString()).isEqualTo("wait_1");
        assertThat(second.get("current_node_name").asString()).isEqualTo("舊-等待");
        assertThat(second.get("current_node_type").asString()).isEqualTo("WaitDelayNode");
        assertThat(t(second.get("enrolled_at"))).isEqualTo(T0);
        assertThat(t(second.get("updated_at"))).isEqualTo(T0);
        assertThat(second.get("terminated_at").isNull()).isTrue();

        JsonNode first = items.get(0);
        assertThat(first.get("journey_version").asInt()).isEqualTo(2);
        assertThat(first.get("journey_name").asString()).isEqualTo("旅程新名");
        // send_1 未命名：current_node_name 為 null
        assertThat(first.get("current_node_name").isNull()).isTrue();
        assertThat(first.get("current_node_type").asString()).isEqualTo("SendMessageNode");
        assertThat(t(first.get("terminated_at"))).isEqualTo(T0.plusSeconds(90));
    }

    @Test
    void list_byEnrollmentId_exactMatchOnly() throws Exception {
        String jid = newJourney("j");
        String profile = ulid();
        String eid = new Enr(jid, profile).save();
        new Enr(jid, profile).save();

        JsonNode items = getOk("/api/v1/enrollments?q=" + eid).get("items");
        assertThat(field(items, "enrollment_id")).containsExactly(eid);

        // 前綴不算命中（精確比對）
        assertThat(getOk("/api/v1/enrollments?q=" + eid.substring(0, 20)).get("items")).isEmpty();
        assertThat(getOk("/api/v1/enrollments?q=" + profile.substring(0, 20)).get("items")).isEmpty();
    }

    @Test
    void list_noMatch_is200EmptyArray() throws Exception {
        JsonNode page = getOk("/api/v1/enrollments?q=" + ulid());
        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void list_statusFilter_singleAndCommaSeparated() throws Exception {
        String jid = newJourney("j");
        String profile = ulid();
        String w = new Enr(jid, profile).status("WAITING").at(T0).save();
        String c = new Enr(jid, profile).status("COMPLETED").at(T0.plusSeconds(1)).save();
        String f = new Enr(jid, profile).status("FAILED").at(T0.plusSeconds(2)).save();

        assertThat(
                        field(
                                getOk("/api/v1/enrollments?q=" + profile + "&status=WAITING").get("items"),
                                "enrollment_id"))
                .containsExactly(w);
        assertThat(
                        field(
                                getOk("/api/v1/enrollments?q=" + profile + "&status=WAITING,COMPLETED")
                                        .get("items"),
                                "enrollment_id"))
                .containsExactly(c, w);
        assertThat(field(getOk("/api/v1/enrollments?q=" + profile).get("items"), "enrollment_id"))
                .containsExactly(f, c, w);
        assertThat(getOk("/api/v1/enrollments?q=" + profile + "&status=ACTIVE").get("items")).isEmpty();
        // 狀態篩選也適用於以 enrollment_id 查詢
        assertThat(getOk("/api/v1/enrollments?q=" + w + "&status=FAILED").get("items")).isEmpty();
    }

    @Test
    void list_cursorPagination_coversAllRowsOnce_includingTies() throws Exception {
        String jid = newJourney("j");
        String profile = ulid();
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            all.add(new Enr(jid, profile).at(T0.plusSeconds(i * 10L)).save());
        }
        // 兩筆 enrolled_at 完全相同，游標不可漏或重
        all.add(new Enr(jid, profile).at(T0.plusSeconds(100)).save());
        all.add(new Enr(jid, profile).at(T0.plusSeconds(100)).save());

        JsonNode p1 = getOk("/api/v1/enrollments?q=" + profile + "&limit=2");
        assertThat(p1.get("items")).hasSize(2);
        assertThat(p1.get("next_cursor").isNull()).isFalse();

        List<String> seen = new ArrayList<>(field(p1.get("items"), "enrollment_id"));
        String cursor = p1.get("next_cursor").asString();
        int pages = 1;
        while (cursor != null) {
            JsonNode p = getOk("/api/v1/enrollments?q=" + profile + "&limit=2&cursor=" + enc(cursor));
            seen.addAll(field(p.get("items"), "enrollment_id"));
            cursor = p.get("next_cursor").isNull() ? null : p.get("next_cursor").asString();
            assertThat(++pages).isLessThan(10);
        }
        assertThat(seen).hasSize(6).containsExactlyInAnyOrderElementsOf(all);
        assertThat(new HashSet<>(seen)).hasSize(6);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void list_defaultLimitIs20_andMaxIs100() throws Exception {
        String jid = newJourney("j");
        String profile = ulid();
        for (int i = 0; i < 25; i++) {
            new Enr(jid, profile).at(T0.plusSeconds(i)).save();
        }
        JsonNode p = getOk("/api/v1/enrollments?q=" + profile);
        assertThat(p.get("items")).hasSize(20);
        assertThat(p.get("next_cursor").isNull()).isFalse();
        assertThat(getOk("/api/v1/enrollments?q=" + profile + "&limit=100").get("items")).hasSize(25);
    }

    @Test
    void list_invalidParameters_return400ValidationFailed() throws Exception {
        for (String bad :
                List.of(
                        "/api/v1/enrollments?q=",
                        "/api/v1/enrollments?q=x&limit=0",
                        "/api/v1/enrollments?q=x&limit=101",
                        "/api/v1/enrollments?q=x&limit=abc",
                        "/api/v1/enrollments?q=x&status=BOGUS",
                        "/api/v1/enrollments?q=x&status=WAITING,BOGUS",
                        "/api/v1/enrollments?q=x&cursor=%21%21not-a-cursor")) {
            assertProblem(get(bad), 400, "VALIDATION_FAILED");
        }
        JsonNode p = json.readTree(get("/api/v1/enrollments?q=x&cursor=%21%21not-a-cursor").body());
        assertThat(field(p.get("errors"), "field")).contains("cursor");
    }

    // ---------- §4.1 詳情 ----------

    @Test
    void detail_waitingEnrollment_hasAllContractFields() throws Exception {
        String jid = ulid();
        seedJourney(jid, 1, "棄單挽回", "v1");
        String profile = ulid();
        Instant wakeAt = T0.plus(Duration.ofDays(3));
        String eid = new Enr(jid, profile).timer(wakeAt).save();
        seedTrace(
                eid,
                1,
                "src_1",
                "EventSourceNode",
                T0,
                T0,
                "next",
                "收到事件 cart_item_added，建立 Enrollment。",
                Map.of("event_id", "01JCXYZ8K3M4N5P6Q7R8S9T0AB"));
        seedTrace(
                eid,
                2,
                "wait_1",
                "WaitDelayNode",
                T0,
                null,
                null,
                "等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。",
                Map.of("duration", "P3D", "wake_at", wakeAt.toString()));

        clock.advance(Duration.ofSeconds(5));
        JsonNode d = getOk("/api/v1/enrollments/" + eid);

        assertThat(d.get("enrollment_id").asString()).isEqualTo(eid);
        assertThat(d.get("journey_id").asString()).isEqualTo(jid);
        assertThat(d.get("journey_version").asInt()).isEqualTo(1);
        assertThat(d.get("journey_name").asString()).isEqualTo("棄單挽回");
        assertThat(d.get("profile_id").asString()).isEqualTo(profile);
        assertThat(d.get("status").asString()).isEqualTo("WAITING");
        assertThat(d.get("current_node_id").asString()).isEqualTo("wait_1");
        assertThat(d.get("current_node_name").asString()).isEqualTo("v1-等待");
        assertThat(d.get("current_node_type").asString()).isEqualTo("WaitDelayNode");
        assertThat(t(d.get("enrolled_at"))).isEqualTo(T0);
        assertThat(t(d.get("updated_at"))).isEqualTo(T0);
        assertThat(d.get("terminated_at").isNull()).isTrue();
        assertThat(d.get("termination_reason").isNull()).isTrue();

        JsonNode wc = d.get("wake_condition");
        assertThat(wc.get("type").asString()).isEqualTo("TIMER");
        assertThat(t(wc.get("wake_at"))).isEqualTo(wakeAt);
        for (String k : List.of("event_name", "signal_name", "timeout_at")) {
            assertThat(wc.has(k)).as(k).isTrue();
            assertThat(wc.get(k).isNull()).as(k).isTrue();
        }

        assertThat(d.get("variables").isObject()).isTrue();
        assertThat(d.get("variables")).isEmpty();

        JsonNode ts = d.get("trigger_snapshot");
        assertThat(ts.get("event_id").asString()).isEqualTo("01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        assertThat(ts.get("event_name").asString()).isEqualTo("cart_item_added");
        assertThat(t(ts.get("occurred_at"))).isEqualTo(Instant.parse("2026-10-18T01:59:41Z"));
        assertThat(ts.get("source_type").asString()).isEqualTo("CUSTOMER_BEHAVIOR");
        assertThat(ts.get("properties").get("cart_id").asString()).isEqualTo("CART-1");
        assertThat(ts.get("profile_snapshot").isObject()).isTrue();

        JsonNode trace = d.get("trace");
        assertThat(trace).hasSize(2);
        JsonNode t1 = trace.get(0);
        assertThat(t1.get("seq").asInt()).isEqualTo(1);
        assertThat(t1.get("node_id").asString()).isEqualTo("src_1");
        assertThat(t1.get("node_type").asString()).isEqualTo("EventSourceNode");
        assertThat(t1.get("node_name").asString()).isEqualTo("v1-來源");
        assertThat(t(t1.get("entered_at"))).isEqualTo(T0);
        assertThat(t(t1.get("exited_at"))).isEqualTo(T0);
        assertThat(t1.get("out_port").asString()).isEqualTo("next");
        assertThat(t1.get("decision_reason").asString())
                .isEqualTo("收到事件 cart_item_added，建立 Enrollment。");
        assertThat(t1.get("evaluated").get("event_id").asString())
                .isEqualTo("01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        JsonNode t2 = trace.get(1);
        assertThat(t2.get("seq").asInt()).isEqualTo(2);
        assertThat(t2.get("exited_at").isNull()).isTrue();
        assertThat(t2.get("out_port").isNull()).isTrue();
        assertThat(t2.get("evaluated").get("duration").asString()).isEqualTo("P3D");
        assertThat(t2.get("evaluated").get("wake_at").asString()).isEqualTo(wakeAt.toString());

        assertThat(d.get("delivery_history").isArray()).isTrue();
        assertThat(d.get("delivery_history")).isEmpty();
        assertThat(d.get("delivery_history_next_cursor").isNull()).isTrue();
        assertThat(d.get("masked_paths").isArray()).isTrue();
        assertThat(d.get("masked_paths")).isEmpty();
        // as_of 取注入的 Clock（模擬時間），不是系統時間
        assertThat(t(d.get("as_of"))).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void detail_asOfFollowsInjectedClock() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        assertThat(t(getOk("/api/v1/enrollments/" + eid).get("as_of"))).isEqualTo(T0);
        clock.advance(Duration.ofHours(10));
        assertThat(t(getOk("/api/v1/enrollments/" + eid).get("as_of")))
                .isEqualTo(T0.plus(Duration.ofHours(10)));
    }

    @Test
    void detail_terminatedEnrollment_hasNullWakeCondition_andTerminationReason() throws Exception {
        String reason = "已完成旅程，於 2026-10-21T02:00:00Z 結束。";
        String eid =
                new Enr(newJourney("j"), ulid())
                        .status("COMPLETED")
                        .node("end_1")
                        .terminated(T0.plusSeconds(30), reason)
                        .save();
        JsonNode d = getOk("/api/v1/enrollments/" + eid);
        assertThat(d.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(d.get("wake_condition").isNull()).isTrue();
        assertThat(t(d.get("terminated_at"))).isEqualTo(T0.plusSeconds(30));
        assertThat(d.get("termination_reason").asString()).isEqualTo(reason);
    }

    @Test
    void detail_traceIsOrderedBySeqOldestFirst() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        // 故意倒序寫入
        seedTrace(eid, 3, "send_1", "SendMessageNode", T0, T0, "sent", "r3", Map.of());
        seedTrace(eid, 1, "src_1", "EventSourceNode", T0, T0, "next", "r1", Map.of());
        seedTrace(eid, 2, "wait_1", "WaitDelayNode", T0, T0, "next", "r2", Map.of());
        JsonNode trace = getOk("/api/v1/enrollments/" + eid).get("trace");
        assertThat(trace).hasSize(3);
        assertThat(trace.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(trace.get(1).get("seq").asInt()).isEqualTo(2);
        assertThat(trace.get(2).get("seq").asInt()).isEqualTo(3);
        // 未命名節點 node_name = null；evaluated 可為 {}
        assertThat(trace.get(2).get("node_name").isNull()).isTrue();
        assertThat(trace.get(2).get("evaluated").isObject()).isTrue();
        assertThat(trace.get(2).get("evaluated")).isEmpty();
    }

    @Test
    void detail_notFound_is404EnrollmentNotFound() throws Exception {
        assertProblem(get("/api/v1/enrollments/" + NO_SUCH), 404, "ENROLLMENT_NOT_FOUND");
        assertProblem(get("/api/v1/enrollments/" + NO_SUCH + "/deliveries"), 404, "ENROLLMENT_NOT_FOUND");
    }

    // ---------- 發送紀錄 ----------

    @Test
    void detail_deliveryHistory_isNewest50_withContinuationCursor() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        seedTrace(eid, 3, "send_1", "SendMessageNode", T0, T0, "sent", "r", Map.of());
        for (int i = 0; i < 55; i++) {
            seedDelivery(
                    eid, eid + ":send_1:3:" + i, "send_1", 3, "SENT", null, "msg-" + i, T0.plusSeconds(i));
        }

        JsonNode d = getOk("/api/v1/enrollments/" + eid);
        JsonNode hist = d.get("delivery_history");
        assertThat(hist).hasSize(50);
        assertThat(hist.get(0).get("idempotency_key").asString()).isEqualTo(eid + ":send_1:3:54");
        assertThat(hist.get(49).get("idempotency_key").asString()).isEqualTo(eid + ":send_1:3:5");
        assertThat(d.get("delivery_history_next_cursor").isNull()).isFalse();

        String cursor = d.get("delivery_history_next_cursor").asString();
        JsonNode rest = getOk("/api/v1/enrollments/" + eid + "/deliveries?cursor=" + enc(cursor));
        assertThat(field(rest.get("items"), "idempotency_key"))
                .containsExactly(
                        eid + ":send_1:3:4",
                        eid + ":send_1:3:3",
                        eid + ":send_1:3:2",
                        eid + ":send_1:3:1",
                        eid + ":send_1:3:0");
        assertThat(rest.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void detail_exactly50Deliveries_hasNoNextCursor() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        for (int i = 0; i < 50; i++) {
            seedDelivery(eid, eid + ":k:" + i, "send_1", 3, "SENT", null, "m" + i, T0.plusSeconds(i));
        }
        JsonNode d = getOk("/api/v1/enrollments/" + eid);
        assertThat(d.get("delivery_history")).hasSize(50);
        assertThat(d.get("delivery_history_next_cursor").isNull()).isTrue();
    }

    @Test
    void deliveries_paginateWithLimit_defaultIs50_coverAllOnce() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        for (int i = 0; i < 55; i++) {
            seedDelivery(eid, eid + ":k:" + i, "send_1", 3, "SENT", null, "m" + i, T0.plusSeconds(i));
        }
        JsonNode dflt = getOk("/api/v1/enrollments/" + eid + "/deliveries");
        assertThat(dflt.get("items")).hasSize(50);
        assertThat(dflt.get("next_cursor").isNull()).isFalse();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path =
                    "/api/v1/enrollments/"
                            + eid
                            + "/deliveries?limit=10"
                            + (cursor == null ? "" : "&cursor=" + enc(cursor));
            JsonNode p = getOk(path);
            seen.addAll(field(p.get("items"), "idempotency_key"));
            cursor = p.get("next_cursor").isNull() ? null : p.get("next_cursor").asString();
            assertThat(++pages).isLessThan(10);
        } while (cursor != null);
        assertThat(pages).isEqualTo(6);
        assertThat(seen).hasSize(55);
        assertThat(new HashSet<>(seen)).hasSize(55);
        assertThat(seen.get(0)).isEqualTo(eid + ":k:54");
        assertThat(seen.get(54)).isEqualTo(eid + ":k:0");
    }

    @Test
    void deliveries_entryFields_andStatusMapping() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        seedTrace(eid, 3, "send_1", "SendMessageNode", T0, T0, "sent", "r", Map.of());
        seedDelivery(eid, eid + ":sent", "send_1", 3, "SENT", null, "mock-msg-1", T0.plusSeconds(3));
        seedDelivery(eid, eid + ":skip", "wait_1", 2, "SKIPPED", "CONSENT", null, T0.plusSeconds(2));
        seedDelivery(eid, eid + ":pend", "send_1", 3, "PENDING", null, null, T0.plusSeconds(1));

        JsonNode items = getOk("/api/v1/enrollments/" + eid + "/deliveries").get("items");
        assertThat(items).hasSize(3);

        JsonNode sent = items.get(0);
        assertThat(sent.get("idempotency_key").asString()).isEqualTo(eid + ":sent");
        assertThat(sent.get("node_id").asString()).isEqualTo("send_1");
        assertThat(sent.get("trace_seq").asInt()).isEqualTo(3);
        assertThat(sent.get("node_name").isNull()).isTrue();
        assertThat(sent.get("template_id").asString()).isEqualTo("tpl_a");
        assertThat(sent.get("channel_id").asString()).isEqualTo("ch_a");
        assertThat(sent.get("channel_type").asString()).isEqualTo("MOCK");
        assertThat(sent.get("message_type").isNull()).isTrue();
        assertThat(sent.get("status").asString()).isEqualTo("SENT");
        assertThat(sent.get("skip_reason").isNull()).isTrue();
        assertThat(t(sent.get("attempted_at"))).isEqualTo(T0.plusSeconds(3));
        assertThat(sent.get("delivery_id").asString()).isEqualTo("mock-msg-1");

        JsonNode skipped = items.get(1);
        assertThat(skipped.get("status").asString()).isEqualTo("SKIPPED");
        assertThat(skipped.get("skip_reason").asString()).isEqualTo("CONSENT");
        assertThat(skipped.get("node_name").asString()).isEqualTo("v1-等待");
        assertThat(skipped.get("delivery_id").isNull()).isTrue();

        assertThat(items.get(2).get("status").asString()).isEqualTo("PENDING");

        // detail 內的 delivery_history 與 /deliveries 同序
        JsonNode hist = getOk("/api/v1/enrollments/" + eid).get("delivery_history");
        assertThat(field(hist, "idempotency_key"))
                .containsExactly(eid + ":sent", eid + ":skip", eid + ":pend");
    }

    @Test
    void deliveries_invalidParameters_return400() throws Exception {
        String eid = new Enr(newJourney("j"), ulid()).save();
        for (String q : List.of("limit=0", "limit=101", "limit=abc", "cursor=%21%21bad")) {
            assertProblem(get("/api/v1/enrollments/" + eid + "/deliveries?" + q), 400, "VALIDATION_FAILED");
        }
        JsonNode p = json.readTree(get("/api/v1/enrollments/" + eid + "/deliveries?cursor=%21%21bad").body());
        assertThat(field(p.get("errors"), "field")).contains("cursor");
    }

    // ---------- §4.4 遮罩 ----------

    @Test
    void masking_nonWhitelistMasked_keysKept_pathsListed_whitelistSubtreePlain() throws Exception {
        String jid = newJourney("j");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cart_id", "CART-99201");
        props.put("cart_total", 1580);
        props.put("email", "alice@example.com");
        props.put("phone", "0912345678");
        props.put("age", 30);
        props.put("vip", true);
        props.put("note", null);
        props.put("duration", "P3D"); // 引擎鍵只對 evaluated 明文，事件屬性仍遮罩
        props.put(
                "safe_tree",
                Map.of("email", "keep@example.com", "inner", List.of(Map.of("phone", "1"))));
        props.put("address", Map.of("city", "Taipei", "cart_id", "C-IN", "geo", List.of(1, 2)));
        props.put("items", List.of(Map.of("name", "A", "cart_total", 5), Map.of("name", "B")));
        props.put("tags", List.of("x", "y"));
        props.put("odd", "not an email@x.com");
        props.put("short", "a@b");
        Map<String, Object> profileSnap = Map.of("email", "p@example.com", "cart_id", "PS-1");
        Map<String, Object> vars = Map.of("coupon", "SAVE10", "cart_total", 3);

        String eid = new Enr(jid, ulid()).withTrigger(props, profileSnap).variables(vars).save();
        seedTrace(
                eid,
                1,
                "src_1",
                "EventSourceNode",
                T0,
                T0,
                "next",
                "r1",
                Map.of(
                        "event_id",
                        "01JCXYZ8K3M4N5P6Q7R8S9T0AB",
                        "event_name",
                        "cart_item_added",
                        "email",
                        "e@x.io"));
        Map<String, Object> ev2 = new LinkedHashMap<>();
        ev2.put("duration", "P3D");
        ev2.put("wake_at", "2026-10-21T02:00:00Z");
        ev2.put("template_id", "tpl_a");
        ev2.put("channel_id", "ch_a");
        ev2.put("idempotency_key", eid + ":send_1:3:0");
        ev2.put("phone", "0987");
        ev2.put("cart_id", "c-plain");
        seedTrace(eid, 2, "wait_1", "WaitDelayNode", T0, null, null, "r2", ev2);

        JsonNode d = getOk("/api/v1/enrollments/" + eid);
        JsonNode p = d.get("trigger_snapshot").get("properties");

        // 白名單內：明文，整棵子樹（含子樹內的 email）
        assertThat(p.get("cart_id").asString()).isEqualTo("CART-99201");
        assertThat(p.get("cart_total").asInt()).isEqualTo(1580);
        assertThat(p.get("safe_tree").get("email").asString()).isEqualTo("keep@example.com");
        assertThat(p.get("safe_tree").get("inner").get(0).get("phone").asString()).isEqualTo("1");
        // 逐層比對：外層不在白名單，內層鍵在白名單 -> 該內層明文
        assertThat(p.get("address").get("cart_id").asString()).isEqualTo("C-IN");
        assertThat(p.get("items").get(0).get("cart_total").asInt()).isEqualTo(5);

        // 白名單外：遮罩，鍵名保留，值一律字串
        assertThat(p.get("email").asString()).isEqualTo("a***@example.com");
        assertThat(p.get("short").asString()).isEqualTo("a***@b");
        assertThat(p.get("phone").asString()).isEqualTo("***");
        assertThat(p.get("age").isString()).isTrue();
        assertThat(p.get("age").asString()).isEqualTo("***");
        assertThat(p.get("vip").isString()).isTrue();
        assertThat(p.get("vip").asString()).isEqualTo("***");
        assertThat(p.has("note")).isTrue();
        assertThat(p.get("note").isNull()).isTrue();
        assertThat(p.get("duration").asString()).isEqualTo("***");
        assertThat(p.get("odd").asString()).isEqualTo("***");
        assertThat(p.get("address").get("city").asString()).isEqualTo("***");
        assertThat(p.get("address").get("geo").get(0).asString()).isEqualTo("***");
        assertThat(p.get("address").get("geo").get(1).asString()).isEqualTo("***");
        assertThat(p.get("items").get(0).get("name").asString()).isEqualTo("***");
        assertThat(p.get("items").get(1).get("name").asString()).isEqualTo("***");
        assertThat(p.get("tags")).hasSize(2);
        assertThat(p.get("tags").get(0).asString()).isEqualTo("***");
        assertThat(p.get("tags").get(1).asString()).isEqualTo("***");

        // profile_snapshot、variables 同規則
        JsonNode ps = d.get("trigger_snapshot").get("profile_snapshot");
        assertThat(ps.get("email").asString()).isEqualTo("p***@example.com");
        assertThat(ps.get("cart_id").asString()).isEqualTo("PS-1");
        assertThat(d.get("variables").get("coupon").asString()).isEqualTo("***");
        assertThat(d.get("variables").get("cart_total").asInt()).isEqualTo(3);

        // trace.evaluated：白名單鍵＋引擎鍵明文，其餘遮罩
        JsonNode e1 = d.get("trace").get(0).get("evaluated");
        assertThat(e1.get("event_id").asString()).isEqualTo("01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        assertThat(e1.get("event_name").asString()).isEqualTo("cart_item_added");
        assertThat(e1.get("email").asString()).isEqualTo("e***@x.io");
        JsonNode e2 = d.get("trace").get(1).get("evaluated");
        assertThat(e2.get("duration").asString()).isEqualTo("P3D");
        assertThat(e2.get("wake_at").asString()).isEqualTo("2026-10-21T02:00:00Z");
        assertThat(e2.get("template_id").asString()).isEqualTo("tpl_a");
        assertThat(e2.get("channel_id").asString()).isEqualTo("ch_a");
        assertThat(e2.get("idempotency_key").asString()).isEqualTo(eid + ":send_1:3:0");
        assertThat(e2.get("cart_id").asString()).isEqualTo("c-plain");
        assertThat(e2.get("phone").asString()).isEqualTo("***");

        // 其他欄位全部明文
        assertThat(d.get("trigger_snapshot").get("event_id").asString())
                .isEqualTo("01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        assertThat(d.get("trigger_snapshot").get("event_name").asString()).isEqualTo("cart_item_added");
        assertThat(d.get("trace").get(1).get("decision_reason").asString()).isEqualTo("r2");

        List<String> expected =
                List.of(
                        "trigger_snapshot.properties.email",
                        "trigger_snapshot.properties.phone",
                        "trigger_snapshot.properties.age",
                        "trigger_snapshot.properties.vip",
                        "trigger_snapshot.properties.duration",
                        "trigger_snapshot.properties.address.city",
                        "trigger_snapshot.properties.address.geo[0]",
                        "trigger_snapshot.properties.address.geo[1]",
                        "trigger_snapshot.properties.items[0].name",
                        "trigger_snapshot.properties.items[1].name",
                        "trigger_snapshot.properties.tags[0]",
                        "trigger_snapshot.properties.tags[1]",
                        "trigger_snapshot.properties.odd",
                        "trigger_snapshot.properties.short",
                        "trigger_snapshot.profile_snapshot.email",
                        "variables.coupon",
                        "trace[0].evaluated.email",
                        "trace[1].evaluated.phone");
        List<String> actual = new ArrayList<>(strings(d.get("masked_paths")));
        // null 保持 null、不是被遮罩的值：契約未規定是否列入，這裡不斷言
        actual.remove("trigger_snapshot.properties.note");
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void masking_sameForAdminAndSupport_rawPiiNeverInResponse() throws Exception {
        String eid =
                new Enr(newJourney("j"), ulid())
                        .withTrigger(Map.of("email", "zed@example.org", "cart_id", "K"), Map.of())
                        .save();
        String viaAdmin = get("/api/v1/enrollments/" + eid).body();
        HttpResponse<String> viaSession =
                call("GET", "/api/v1/enrollments/" + eid, null, sessionCookie(), null);
        assertThat(viaSession.statusCode()).isEqualTo(200);
        for (String body : List.of(viaAdmin, viaSession.body())) {
            JsonNode d = json.readTree(body);
            JsonNode props = d.get("trigger_snapshot").get("properties");
            assertThat(props.get("email").asString()).isEqualTo("z***@example.org");
            assertThat(props.get("cart_id").asString()).isEqualTo("K");
            assertThat(strings(d.get("masked_paths"))).containsExactly("trigger_snapshot.properties.email");
            assertThat(body).doesNotContain("zed@example.org");
        }
    }

    @Test
    void masking_doesNotApplyToSummaryOrDeliveries() throws Exception {
        String profile = ulid();
        String eid = new Enr(newJourney("j"), profile).save();
        seedDelivery(eid, eid + ":k", "send_1", 3, "SENT", null, "msg-plain", T0);
        JsonNode item = getOk("/api/v1/enrollments?q=" + profile).get("items").get(0);
        assertThat(item.get("profile_id").asString()).isEqualTo(profile);
        assertThat(item.get("enrollment_id").asString()).isEqualTo(eid);
        JsonNode dl = getOk("/api/v1/enrollments/" + eid + "/deliveries").get("items").get(0);
        assertThat(dl.get("delivery_id").asString()).isEqualTo("msg-plain");
        assertThat(dl.get("idempotency_key").asString()).isEqualTo(eid + ":k");
        assertThat(getOk("/api/v1/enrollments/" + eid).get("profile_id").asString()).isEqualTo(profile);
    }

    // ---------- §1.1 認證與角色 ----------

    @Test
    void auth_adminKeyAndSupportSession_canQuery_ingestKey_gets403() throws Exception {
        String profile = ulid();
        String eid = new Enr(newJourney("j"), profile).save();
        String cookie = sessionCookie();
        List<String> paths =
                List.of(
                        "/api/v1/enrollments?q=" + profile,
                        "/api/v1/enrollments/" + eid,
                        "/api/v1/enrollments/" + eid + "/deliveries");
        for (String path : paths) {
            assertThat(call("GET", path, ADMIN, null, null).statusCode()).as("admin " + path).isEqualTo(200);
            assertThat(call("GET", path, null, cookie, null).statusCode()).as("session " + path).isEqualTo(200);
            assertProblem(call("GET", path, INGEST, null, null), 403, "FORBIDDEN");
        }
    }

    @Test
    void auth_unauthenticated_is401BeforeValidationOr404() throws Exception {
        assertProblem(call("GET", "/api/v1/enrollments?q=x", null, null, null), 401, "UNAUTHENTICATED");
        // 先驗認證再驗輸入：參數錯誤、資料不存在時未認證仍是 401
        assertProblem(call("GET", "/api/v1/enrollments?limit=999", null, null, null), 401, "UNAUTHENTICATED");
        assertProblem(call("GET", "/api/v1/enrollments/" + NO_SUCH, null, null, null), 401, "UNAUTHENTICATED");
        assertProblem(
                call("GET", "/api/v1/enrollments/" + NO_SUCH + "/deliveries?limit=0", null, null, null),
                401,
                "UNAUTHENTICATED");
        assertProblem(
                call("GET", "/api/v1/enrollments?q=x", "Bearer wrong-key", null, null),
                401,
                "UNAUTHENTICATED");
        // INGEST 金鑰：403 先於輸入驗證的 400
        assertProblem(call("GET", "/api/v1/enrollments?limit=999", INGEST, null, null), 403, "FORBIDDEN");
    }

    // ---------- 真實寫入路徑端到端 ----------

    private HttpResponse<String> post(String path, String auth, Object body) throws Exception {
        return call("POST", path, auth, null, js(body));
    }

    @Test
    void endToEnd_realWritePath_isQueryableWithMaskingAndInjectedClock() throws Exception {
        String eventName = "evt_" + ulid().toLowerCase();
        Map<String, Object> journey = new LinkedHashMap<>();
        journey.put("name", "棄單挽回-e2e");
        journey.put("settings", Map.of());
        journey.put("entry_node", "start_1");
        journey.put(
                "nodes",
                List.of(
                        node("start_1", "StartNode", null, Map.of(), Map.of("next", "src_1")),
                        node(
                                "src_1",
                                "EventSourceNode",
                                "加入購物車",
                                Map.of("event_name", eventName),
                                Map.of("next", "wait_1")),
                        node(
                                "wait_1",
                                "WaitDelayNode",
                                "等一小時",
                                Map.of("duration", "PT1H"),
                                Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                "發提醒",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", null, Map.of(), Map.of())));
        assertThat(post("/api/v1/journeys", ADMIN, journey).statusCode()).isEqualTo(201);

        String profile = ulid();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event_id", ulid());
        ev.put("source_type", "CUSTOMER_BEHAVIOR");
        ev.put("source_id", "web-sdk@2.1.0");
        ev.put("name", eventName);
        ev.put("occurred_at", clock.instant().minusSeconds(60).toString());
        ev.put("identity", Map.of("profile_id", profile));
        ev.put("properties", Map.of("cart_id", "CART-7", "email", "secret-value-77@example.com"));
        assertThat(post("/api/v1/events", INGEST, ev).statusCode()).isEqualTo(202);
        dispatcher.runOnce();

        JsonNode items = getOk("/api/v1/enrollments?q=" + profile).get("items");
        assertThat(items).hasSize(1);
        JsonNode s = items.get(0);
        assertThat(s.get("status").asString()).isEqualTo("WAITING");
        assertThat(s.get("journey_name").asString()).isEqualTo("棄單挽回-e2e");
        assertThat(s.get("current_node_id").asString()).isEqualTo("wait_1");
        assertThat(s.get("current_node_name").asString()).isEqualTo("等一小時");
        assertThat(s.get("current_node_type").asString()).isEqualTo("WaitDelayNode");
        String eid = s.get("enrollment_id").asString();

        HttpResponse<String> raw = get("/api/v1/enrollments/" + eid);
        assertThat(raw.body()).doesNotContain("secret-value-77");
        JsonNode d = json.readTree(raw.body());
        assertThat(d.get("wake_condition").get("type").asString()).isEqualTo("TIMER");
        assertThat(t(d.get("wake_condition").get("wake_at"))).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(d.get("trace")).hasSize(2);
        assertThat(d.get("trace").get(1).get("exited_at").isNull()).isTrue();
        assertThat(d.get("trace").get(0).get("node_name").asString()).isEqualTo("加入購物車");
        for (JsonNode tr : d.get("trace")) {
            assertThat(tr.get("decision_reason").asString()).isNotBlank();
        }
        assertThat(d.get("trigger_snapshot").get("event_name").asString()).isEqualTo(eventName);
        assertThat(d.get("trigger_snapshot").get("properties").get("cart_id").asString())
                .isEqualTo("CART-7");
        assertThat(d.get("trigger_snapshot").get("properties").get("email").asString())
                .isEqualTo("s***@example.com");
        assertThat(strings(d.get("masked_paths"))).contains("trigger_snapshot.properties.email");
        assertThat(t(d.get("as_of"))).isEqualTo(T0);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();
        JsonNode after = getOk("/api/v1/enrollments/" + eid);
        assertThat(after.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(after.get("wake_condition").isNull()).isTrue();
        assertThat(after.get("terminated_at").isNull()).isFalse();
        assertThat(after.get("trace")).hasSize(4);
        assertThat(after.get("trace").get(3).get("node_type").asString()).isEqualTo("EndNode");
        assertThat(t(after.get("as_of"))).isEqualTo(T0.plus(Duration.ofHours(1)).plusSeconds(1));
        assertThat(getOk("/api/v1/enrollments?q=" + profile + "&status=WAITING").get("items")).isEmpty();
        assertThat(getOk("/api/v1/enrollments?q=" + profile + "&status=COMPLETED").get("items"))
                .hasSize(1);
    }
}
