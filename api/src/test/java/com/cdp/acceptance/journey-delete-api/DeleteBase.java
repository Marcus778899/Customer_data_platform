package com.cdp.acceptance.journeydeleteapi;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static org.assertj.core.api.Assertions.assertThat;

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

/** journey-delete-api 驗收共用：真實 HTTP（隨機 port）＋Testcontainers。journeys.md §3.6。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class DeleteBase {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;

    protected record Actor(String cookie, String userId) {}

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    protected Actor actor(String role) throws Exception {
        String email = AuthHttp.uniqueEmail();
        String id = AuthHttp.createUserId(port, email, role);
        return new Actor(AuthHttp.login(port, email, AuthHttp.PASSWORD), id);
    }

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
        assertThat(r.json().path("code").asString()).isEqualTo(code);
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
        return arr.valueStream().map(n -> n.path(name).asString()).toList();
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

    /** start, src_1, wait_1, end_1（可通過驗證）。 */
    protected static Map<String, Object> plainDef(String name, String eventName) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("description", null);
        d.put("settings", Map.of());
        d.put("entry_node", "start");
        d.put(
                "nodes",
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", eventName), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        return d;
    }

    protected String newDraft(Actor a) throws Exception {
        return ok(as(a, "POST", "/api/v1/journeys/drafts", Map.of("name", "刪除-" + uniq())), 201)
                .path("journey_id")
                .asString();
    }

    protected Resp saveRaw(Actor a, String id, int revision) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", revision);
        b.put("definition", plainDef("刪除-" + uniq(), "evt_" + uniq()));
        b.put("layout", null);
        return as(a, "PUT", "/api/v1/journeys/" + id + "/draft", b);
    }

    protected int save(Actor a, String id, int revision) throws Exception {
        return ok(saveRaw(a, id, revision), 200).path("revision").asInt();
    }

    protected Resp submitRaw(Actor a, String id, int revision) throws Exception {
        return as(a, "POST", "/api/v1/journeys/" + id + "/draft/submit", Map.of("revision", revision));
    }

    protected String submit(Actor a, String id, int revision) throws Exception {
        return ok(submitRaw(a, id, revision), 201).path("review_id").asString();
    }

    protected Resp review(Actor a, String id, String reviewId, String action, Object body) throws Exception {
        return as(a, "POST", "/api/v1/journeys/" + id + "/reviews/" + reviewId + "/" + action, body);
    }

    protected Resp delete(Actor a, String id) throws Exception {
        return as(a, "DELETE", "/api/v1/journeys/" + id, null);
    }

    protected String publishNew(Actor editor, Actor admin) throws Exception {
        String id = newDraft(editor);
        int rev = save(editor, id, 1);
        ok(review(admin, id, submit(editor, id, rev), "approve", Map.of()), 200);
        return id;
    }

    /** query 為空字串或以 & 結尾的查詢參數。 */
    protected List<String> listIds(Actor a, String query) throws Exception {
        return field(ok(as(a, "GET", "/api/v1/journeys?" + query + "limit=100", null), 200).path("items"), "journey_id");
    }
}
