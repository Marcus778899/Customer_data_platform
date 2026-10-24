package com.cdp.acceptance.profilesearchapi;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * profile-search-api 驗收共用：真實 HTTP（隨機 port）＋Testcontainers＋可調 Clock。明文白名單覆寫為
 * {@code member_id,name,tier,city,note}。電話預設國碼用多個候選屬性名一起設（實作單選其一；未知屬性無害）。
 * 套件名不含連字號（Java 不允許），目錄名 profile-search-api 沿用 user-auth-api 的做法。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "cdp.trace.plain-keys=member_id,name,tier,city,note",
            "cdp.phone.default-country-code=886"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class ProfileStoreBase {

    protected static final JsonMapper JSON = new JsonMapper();
    protected static final Instant T0 = EngineTestSupport.BASE;

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;

    private static String viewerCookie;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- HTTP ----------

    protected Resp putProfile(String id, Object body) throws Exception {
        return AuthHttp.bearer(port, "PUT", "/api/v1/profiles/" + id, INGEST_KEY, js(body));
    }

    protected JsonNode putOk(String id, Object body) throws Exception {
        Resp r = putProfile(id, body);
        assertThat(r.status()).as("PUT %s -> %s", id, r.body()).isEqualTo(200);
        return r.json();
    }

    /** 以管理金鑰讀取（讀取類端點管理金鑰同 ADMIN）。 */
    protected Resp get(String path) throws Exception {
        return AuthHttp.bearer(port, "GET", path, ADMIN_KEY, null);
    }

    protected JsonNode getOk(String path) throws Exception {
        Resp r = get(path);
        assertThat(r.status()).as("GET %s -> %s", path, r.body()).isEqualTo(200);
        return r.json();
    }

    protected Resp post(String path, String auth, Object body) throws Exception {
        return AuthHttp.bearer(port, "POST", path, auth, js(body));
    }

    protected synchronized String viewer() throws Exception {
        if (viewerCookie == null) viewerCookie = AuthHttp.loginNewUser(port, "VIEWER");
        return viewerCookie;
    }

    protected void assertProblem(Resp r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.json().path("code").asString()).isEqualTo(code);
        assertThat(r.json().path("status").asInt()).isEqualTo(status);
    }

    // ---------- 資料 ----------

    protected static String js(Object o) {
        return o instanceof String s ? s : JSON.writeValueAsString(o);
    }

    protected static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    protected static String ulid() {
        return EngineTestSupport.ulid();
    }

    /** 隨機小寫 16 碼，造不重複的識別碼值。 */
    protected static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    protected static Map<String, Object> ident(String type, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("value", value);
        return m;
    }

    protected static Map<String, Object> body(Map<String, Object> attributes, List<?> identifiers) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (attributes != null) m.put("attributes", attributes);
        if (identifiers != null) m.put("identifiers", identifiers);
        return m;
    }

    protected static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asString());
        return out;
    }

    protected static List<String> field(JsonNode arr, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.path(name).asString());
        return out;
    }

    protected static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    // ---------- 旅程與 Enrollment 種子 ----------

    private static Map<String, Object> node(
            String id, String type, String name, Map<String, Object> config, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        if (name != null) n.put("name", name);
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    /** 經 POST /journeys（管理金鑰）建立一個版本；journeyId 為 null = 新旅程。回傳 journey_id。 */
    protected String publish(String journeyId, String name, String description, String waitName) throws Exception {
        Map<String, Object> j = new LinkedHashMap<>();
        if (journeyId != null) j.put("journey_id", journeyId);
        j.put("name", name);
        if (description != null) j.put("description", description);
        j.put("settings", Map.of());
        j.put("entry_node", "start_1");
        j.put(
                "nodes",
                List.of(
                        node("start_1", "StartNode", null, Map.of(), Map.of("next", "src_1")),
                        node(
                                "src_1",
                                "EventSourceNode",
                                "來源",
                                Map.of("event_name", "evt_psa_" + uniq()),
                                Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", waitName, Map.of("duration", "P3D"), Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                null,
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", null, Map.of(), Map.of())));
        Resp r = post("/api/v1/journeys", ADMIN_KEY, j);
        assertThat(r.status()).as("POST /journeys -> %s", r.body()).isEqualTo(201);
        return r.json().path("journey_id").asString();
    }

    protected String enrollment(String journeyId, int version, String profileId, String status, Instant enrolledAt) {
        String id = ulid();
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("eventId", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        trig.put("eventName", "cart_item_added");
        trig.put("occurredAt", "2026-10-18T01:59:41Z");
        trig.put("sourceType", "CUSTOMER_BEHAVIOR");
        trig.put("properties", Map.of());
        trig.put("profileSnapshot", Map.of());
        trig.put("event_id", "01JCXYZ8K3M4N5P6Q7R8S9T0AB");
        trig.put("event_name", "cart_item_added");
        trig.put("occurred_at", "2026-10-18T01:59:41Z");
        trig.put("source_type", "CUSTOMER_BEHAVIOR");
        trig.put("profile_snapshot", Map.of());
        boolean terminal = !List.of("WAITING", "ACTIVE", "PENDING", "SUSPENDED").contains(status);
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                        + " current_node_id, trigger_snapshot, variables, enrolled_at, updated_at, terminated_at)"
                        + " values (?,?,?,?,?,?,?::jsonb,'{}'::jsonb,?,?,?)",
                id,
                journeyId,
                version,
                profileId,
                status,
                "wait_1",
                js(trig),
                ts(enrolledAt),
                ts(enrolledAt),
                ts(terminal ? enrolledAt : null));
        return id;
    }
}
