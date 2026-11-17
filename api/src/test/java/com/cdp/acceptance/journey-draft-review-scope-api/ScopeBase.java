package com.cdp.acceptance.journeydraftreviewscopeapi;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

/** journey-draft-review-scope-api 驗收共用：真實 HTTP（隨機 port）＋Testcontainers＋可調 Clock。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class ScopeBase {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;

    protected record Actor(String cookie, String userId) {}

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    protected void tick() {
        clock.advance(Duration.ofMinutes(1));
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

    protected static String js(Object o) {
        return o instanceof String s ? s : JSON.writeValueAsString(o);
    }

    protected static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
    protected static Map<String, Object> plainDef(String name, String eventName, String waitIso) {
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
                        node("wait_1", "WaitDelayNode", Map.of("duration", waitIso), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        return d;
    }

    protected String newDraft(Actor a, String name) throws Exception {
        return ok(as(a, "POST", "/api/v1/journeys/drafts", Map.of("name", name)), 201)
                .path("journey_id")
                .asString();
    }

    protected Resp saveRaw(Actor a, String journeyId, int revision, Object definition) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", revision);
        b.put("definition", definition);
        b.put("layout", null);
        return as(a, "PUT", "/api/v1/journeys/" + journeyId + "/draft", b);
    }

    protected int save(Actor a, String journeyId, int revision, Map<String, Object> definition) throws Exception {
        return ok(saveRaw(a, journeyId, revision, definition), 200).path("revision").asInt();
    }

    protected String submit(Actor a, String journeyId, int revision) throws Exception {
        return ok(as(a, "POST", "/api/v1/journeys/" + journeyId + "/draft/submit", Map.of("revision", revision)), 201)
                .path("review_id")
                .asString();
    }

    protected JsonNode reviewAction(Actor a, String journeyId, String reviewId, String action, Object body)
            throws Exception {
        return ok(as(a, "POST", "/api/v1/journeys/" + journeyId + "/reviews/" + reviewId + "/" + action, body), 200);
    }

    protected void deleteDraft(Actor a, String journeyId) throws Exception {
        Resp r = as(a, "DELETE", "/api/v1/journeys/" + journeyId + "/draft", null);
        assertThat(r.status()).as(r.body()).isIn(200, 204);
    }

    /** 建草稿、存定義、送審、他人核准；回傳 journey_id（ACTIVE、有 v1、無草稿）。 */
    protected String publishNew(Actor editor, Actor admin) throws Exception {
        String id = newDraft(editor, "範圍-" + uniq());
        int rev = save(editor, id, 1, plainDef("範圍", "evt_" + uniq(), "PT1H"));
        reviewAction(admin, id, submit(editor, id, rev), "approve", Map.of());
        return id;
    }

    protected JsonNode detail(String journeyId) throws Exception {
        return ok(key("GET", "/api/v1/journeys/" + journeyId, null), 200);
    }

    protected JsonNode draft(Actor a, String journeyId) throws Exception {
        return ok(as(a, "GET", "/api/v1/journeys/" + journeyId + "/draft", null), 200);
    }

    protected List<String> listIds(Actor a, String reviewStatus) throws Exception {
        JsonNode items = ok(as(a, "GET", "/api/v1/journeys?review_status=" + reviewStatus + "&limit=100", null), 200)
                .path("items");
        return field(items, "journey_id");
    }

    protected void assertNoReview(Actor a, String journeyId) throws Exception {
        JsonNode d = detail(journeyId);
        assertThat(d.path("review_status").isNull()).as("review_status").isTrue();
        assertThat(d.path("latest_review").isNull()).as("latest_review").isTrue();
        assertThat(draft(a, journeyId).path("latest_review").isNull()).as("Draft.latest_review").isTrue();
        assertThat(listIds(a, "REJECTED")).doesNotContain(journeyId);
        assertThat(listIds(a, "PENDING")).doesNotContain(journeyId);
    }

    protected void assertRejected(Actor a, String journeyId, String reviewId) throws Exception {
        JsonNode d = detail(journeyId);
        assertThat(d.path("review_status").asString()).isEqualTo("REJECTED");
        assertThat(d.path("latest_review").path("review_id").asString()).isEqualTo(reviewId);
        assertThat(draft(a, journeyId).path("latest_review").path("status").asString())
                .isEqualTo("REJECTED");
        assertThat(listIds(a, "REJECTED")).contains(journeyId);
    }
}
