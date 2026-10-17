package com.cdp.acceptance.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cdp.acceptance.engine.EngineTestSupport.MutableClock;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.json.JsonMapper;

/**
 * BE-01 驗收：契約 §3.1、§3.2、§5 與引擎直線流程。只用 HTTP、runOnce()、可控 Clock 與 SQL 觀察。
 * 輪詢間隔調成 1 小時，讓推進只由 runOnce() 驅動。
 */
@SpringBootTest(properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class JourneyEngineAcceptanceTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired EventDispatcher dispatcher;
    @Autowired AdvanceLoop advanceLoop;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String key) {
        // 同時帶 Bearer（BE-04 後）與 mock user（BE-04 前沿用 Boot 預設安全設定）
        return b.header("Authorization", "Bearer " + key)
                .with(user("qa").roles("ADMIN", "INGEST"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions postJson(String path, String key, Object body) throws Exception {
        return mvc.perform(authed(post(path), key).content(json.writeValueAsString(body)));
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
        if (name != null) {
            n.put("name", name);
        }
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    private static Map<String, Object> journey(String eventName, String duration) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "棄單挽回");
        j.put("settings", Map.of());
        j.put("entry_node", "start_1");
        j.put(
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
                                Map.of("duration", duration),
                                Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                "發提醒",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", null, Map.of(), Map.of())));
        return j;
    }

    private String createJourney(String eventName) throws Exception {
        postJson("/api/v1/journeys", "test-admin-key", journey(eventName, "PT1H"))
                .andExpect(status().isCreated());
        return eventName;
    }

    private static String newEventName() {
        return "evt_" + EngineTestSupport.ulid().toLowerCase();
    }

    private Map<String, Object> event(String eventId, String name, String profileId, Instant at) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", eventId);
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", name);
        e.put("occurred_at", at.toString());
        e.put("identity", Map.of("profile_id", profileId));
        e.put("properties", Map.of("cart_id", "CART-1", "email", "secret-value-77@example.com"));
        return e;
    }

    private static String ulid() {
        return EngineTestSupport.ulid();
    }

    private ResultActions ingest(Map<String, Object> e) throws Exception {
        return postJson("/api/v1/events", "test-ingest-key", e);
    }

    private String enrollmentId(String profileId) {
        List<String> ids =
                jdbc.queryForList(
                        "select enrollment_id from enrollment_record where profile_id = ?",
                        String.class,
                        profileId);
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private int enrollmentCount(String profileId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where profile_id = ?",
                Integer.class,
                profileId);
    }

    private String enrollmentStatus(String eid) {
        return jdbc.queryForObject(
                "select status from enrollment_record where enrollment_id = ?", String.class, eid);
    }

    private int outboxCount(String eid) {
        return jdbc.queryForObject(
                "select count(*) from effect_outbox where enrollment_id = ?", Integer.class, eid);
    }

    private List<Map<String, Object>> trace(String eid) {
        return jdbc.queryForList(
                "select * from node_trace where enrollment_id = ? order by seq", eid);
    }

    private static List<Long> seqs(List<Map<String, Object>> trace) {
        return trace.stream().map(m -> ((Number) m.get("seq")).longValue()).toList();
    }

    /** 登錄一個旅程並送入一筆事件、派發，回傳 profile_id。 */
    private String enrollViaEvent(String eventName) throws Exception {
        createJourney(eventName);
        String profile = ulid();
        ingest(event(ulid(), eventName, profile, clock.instant().minusSeconds(60)))
                .andExpect(status().isAccepted());
        dispatcher.runOnce();
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodesOf(Map<String, Object> j) {
        return (List<Map<String, Object>>) j.get("nodes");
    }

    // ---------- §3.1 建立旅程 ----------

    @Test
    void createJourney_returns201_andSameIdBecomesNewVersion() throws Exception {
        Map<String, Object> j = journey(newEventName(), "PT1H");
        String body =
                postJson("/api/v1/journeys", "test-admin-key", j)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.journey_id").isString())
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.published_at").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = json.readTree(body).get("journey_id").asString();

        j.put("journey_id", id);
        postJson("/api/v1/journeys", "test-admin-key", j)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journey_id").value(id))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void createJourney_rejectsInvalidDefinitions_with400() throws Exception {
        String ev = newEventName();

        Map<String, Object> noName = journey(ev, "PT1H");
        noName.remove("name");
        expectValidationFailed(noName);

        Map<String, Object> badTarget = journey(ev, "PT1H");
        List<Map<String, Object>> nodes = new ArrayList<>(nodesOf(badTarget));
        nodes.set(0, node("start_1", "StartNode", null, Map.of(), Map.of("next", "ghost")));
        badTarget.put("nodes", nodes);
        expectValidationFailed(badTarget);

        expectValidationFailed(journey(ev, "PT0S"));
        expectValidationFailed(journey(ev, "not-a-duration"));

        Map<String, Object> unsupported = journey(ev, "PT1H");
        nodes = new ArrayList<>(nodesOf(unsupported));
        nodes.set(
                2,
                node(
                        "wait_1",
                        "ConditionNode",
                        null,
                        Map.of(),
                        Map.of("yes", "send_1", "no", "send_1")));
        unsupported.put("nodes", nodes);
        expectValidationFailed(unsupported);

        Map<String, Object> cyclic = journey(ev, "PT1H");
        nodes = new ArrayList<>(nodesOf(cyclic));
        nodes.set(
                2,
                node(
                        "wait_1",
                        "WaitDelayNode",
                        null,
                        Map.of("duration", "PT1H"),
                        Map.of("next", "src_1")));
        cyclic.put("nodes", nodes);
        expectValidationFailed(cyclic);

        Map<String, Object> noEventName = journey(ev, "PT1H");
        nodes = new ArrayList<>(nodesOf(noEventName));
        nodes.set(1, node("src_1", "EventSourceNode", null, Map.of(), Map.of("next", "wait_1")));
        noEventName.put("nodes", nodes);
        expectValidationFailed(noEventName);

        Map<String, Object> sendNoSent = journey(ev, "PT1H");
        nodes = new ArrayList<>(nodesOf(sendNoSent));
        nodes.set(
                3,
                node(
                        "send_1",
                        "SendMessageNode",
                        null,
                        Map.of("template_id", "t", "channel_id", "c"),
                        Map.of("skipped", "end_1")));
        sendNoSent.put("nodes", nodes);
        expectValidationFailed(sendNoSent);
    }

    private void expectValidationFailed(Map<String, Object> j) throws Exception {
        postJson("/api/v1/journeys", "test-admin-key", j)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.request_id").isString())
                .andExpect(jsonPath("$.errors", not(empty())));
    }

    // ---------- §3.2 事件攝取 ----------

    @Test
    void ingestSingle_accepted_thenDuplicate() throws Exception {
        String id = ulid();
        Map<String, Object> e = event(id, newEventName(), ulid(), clock.instant().minusSeconds(5));
        ingest(e)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_id").value(id))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.errors", empty()))
                .andExpect(header().exists("X-Request-Id"));
        ingest(e)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event where event_id = ?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        // received_at 取自注入的 Clock
        assertThat(
                        jdbc.queryForObject(
                                        "select received_at from event where event_id = ?",
                                        OffsetDateTime.class,
                                        id)
                                .toInstant())
                .isEqualTo(clock.instant());
    }

    @Test
    void ingestSingle_validationErrors_return400WithFieldPaths() throws Exception {
        Instant now = clock.instant();
        String name = newEventName();

        Map<String, Object> noProfile = event(ulid(), name, "x", now);
        noProfile.put(
                "identity",
                Map.of("identifiers", List.of(Map.of("type", "EMAIL", "value", "a@b.co"))));
        expect400(noProfile, "identity.profile_id");

        expect400(event(ulid(), "Bad-Name", ulid(), now), "name");

        expect400(event(ulid(), name, ulid(), now.plus(Duration.ofMinutes(6))), "occurred_at");

        Map<String, Object> noOccurred = event(ulid(), name, ulid(), now);
        noOccurred.remove("occurred_at");
        expect400(noOccurred, "occurred_at");

        Map<String, Object> noEventId = event(ulid(), name, ulid(), now);
        noEventId.remove("event_id");
        expect400(noEventId, "event_id");

        Map<String, Object> internalNoActor = event(ulid(), name, ulid(), now);
        internalNoActor.put("source_type", "INTERNAL_OPERATION");
        internalNoActor.put("source_id", "user-1");
        expect400(internalNoActor, "context.actor_id");

        Map<String, Object> customerWithActor = event(ulid(), name, ulid(), now);
        customerWithActor.put("context", Map.of("actor_id", "u1"));
        expect400(customerWithActor, "context.actor_id");
    }

    private void expect400(Map<String, Object> e, String field) throws Exception {
        ingest(e)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.request_id").isString())
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]", not(empty())));
    }

    @Test
    void requestIdHeader_isEchoedWhenValid_andGeneratedOtherwise() throws Exception {
        String body =
                json.writeValueAsString(event(ulid(), newEventName(), ulid(), clock.instant()));
        mvc.perform(
                        authed(post("/api/v1/events"), "test-ingest-key")
                                .header("X-Request-Id", "my-req.1")
                                .content(body))
                .andExpect(header().string("X-Request-Id", "my-req.1"));
        mvc.perform(
                        authed(post("/api/v1/events"), "test-ingest-key")
                                .header("X-Request-Id", "bad id!")
                                .content(body))
                .andExpect(header().string("X-Request-Id", not("bad id!")))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void batch_partialSuccess_countsDuplicateAsAccepted() throws Exception {
        String name = newEventName();
        Instant now = clock.instant();
        Map<String, Object> ok = event(ulid(), name, ulid(), now);
        Map<String, Object> dup = event(ulid(), name, ulid(), now);
        ingest(dup).andExpect(status().isAccepted());
        Map<String, Object> bad = event(ulid(), "Bad-Name", ulid(), now);

        postJson("/api/v1/events/batch", "test-ingest-key", Map.of("events", List.of(ok, dup, bad)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[2].event_id").value(bad.get("event_id")))
                .andExpect(jsonPath("$.results[2].errors[0].field").isString())
                .andExpect(jsonPath("$.results[2].errors[0].message").isString());
    }

    @Test
    void batch_over500_is400BatchTooLarge() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        String name = newEventName();
        for (int i = 0; i < 501; i++) {
            events.add(event(ulid(), name, ulid(), clock.instant()));
        }
        postJson("/api/v1/events/batch", "test-ingest-key", Map.of("events", events))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATCH_TOO_LARGE"));
    }

    @Test
    void errorResponses_carryRequestIdInBody() throws Exception {
        postJson("/api/v1/events/batch", "test-ingest-key", Map.of("events", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.request_id", matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    // ---------- 引擎直線流程 ----------

    @Test
    void ingestOnly_doesNotCreateEnrollmentSynchronously() throws Exception {
        String name = createJourney(newEventName());
        String profile = ulid();
        ingest(event(ulid(), name, profile, clock.instant())).andExpect(status().isAccepted());
        assertThat(enrollmentCount(profile)).isZero();
    }

    @Test
    void fullFlow_event_wait_fastForward_outbox_completed() throws Exception {
        String profile = enrollViaEvent(newEventName());
        String eid = enrollmentId(profile);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "select status, current_node_id, journey_version, tenant_id, next_wake_at"
                                + " from enrollment_record where enrollment_id = ?",
                        eid);
        assertThat(row.get("status")).isEqualTo("WAITING");
        assertThat(row.get("current_node_id")).isEqualTo("wait_1");
        assertThat(row.get("journey_version")).isEqualTo(1);
        assertThat(row.get("tenant_id")).isEqualTo("01JTESTTENANT0000000000001");
        assertThat(((java.sql.Timestamp) row.get("next_wake_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
        assertThat(outboxCount(eid)).isZero();

        // 時間未到：不喚醒
        clock.advance(Duration.ofMinutes(59));
        advanceLoop.runOnce();
        assertThat(enrollmentStatus(eid)).isEqualTo("WAITING");
        assertThat(outboxCount(eid)).isZero();

        // 快轉過期：喚醒 → SendMessage 寫 outbox → COMPLETED
        clock.advance(Duration.ofMinutes(2));
        assertThat(advanceLoop.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(enrollmentStatus(eid)).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select terminated_at from enrollment_record where enrollment_id = ?",
                                OffsetDateTime.class,
                                eid))
                .isNotNull();

        List<Map<String, Object>> outbox =
                jdbc.queryForList(
                        "select idempotency_key, node_id from effect_outbox where enrollment_id = ?",
                        eid);
        assertThat(outbox).hasSize(1);
        assertThat((String) outbox.get(0).get("idempotency_key"))
                .matches("^" + eid + ":send_1:\\d+:\\d+$");
        assertThat(outbox.get(0).get("node_id")).isEqualTo("send_1");

        // 再跑一次不重複
        advanceLoop.runOnce();
        assertThat(outboxCount(eid)).isEqualTo(1);
        assertThat(enrollmentStatus(eid)).isEqualTo("COMPLETED");
    }

    @Test
    void nodeTrace_writtenEveryStep_withoutPropertyValuesInReasons() throws Exception {
        String profile = enrollViaEvent(newEventName());
        String eid = enrollmentId(profile);

        // 等待中：兩列，第二列（目前節點）尚未離開
        List<Map<String, Object>> t = trace(eid);
        assertThat(t).hasSize(2);
        assertThat(t.get(0).get("node_type")).isEqualTo("EventSourceNode");
        assertThat(t.get(0).get("out_port")).isEqualTo("next");
        assertThat(t.get(0).get("exited_at")).isNotNull();
        assertThat(t.get(1).get("node_type")).isEqualTo("WaitDelayNode");
        assertThat(t.get(1).get("exited_at")).isNull();
        assertThat(t.get(1).get("out_port")).isNull();

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();

        t = trace(eid);
        assertThat(t)
                .extracting(m -> m.get("node_type"))
                .containsExactly("EventSourceNode", "WaitDelayNode", "SendMessageNode", "EndNode");
        assertThat(seqs(t)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(t.get(1).get("exited_at")).isNotNull();
        assertThat(t.get(1).get("out_port")).isEqualTo("next");
        assertThat(t.get(2).get("out_port")).isEqualTo("sent");
        assertThat(t.get(3).get("out_port")).isNull();
        for (Map<String, Object> m : t) {
            String reason = (String) m.get("decision_reason");
            assertThat(reason).isNotBlank().hasSizeLessThanOrEqualTo(200);
            assertThat(reason).doesNotContain("secret-value-77").doesNotContain("CART-1");
            assertThat(String.valueOf(m.get("evaluated"))).doesNotContain("secret-value-77");
        }
    }

    @Test
    void lateEvent_over24h_isAcceptedButCreatesNoEnrollment() throws Exception {
        String name = createJourney(newEventName());
        String profile = ulid();
        String id = ulid();
        ingest(event(id, name, profile, clock.instant().minus(Duration.ofHours(25))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        dispatcher.runOnce();
        assertThat(enrollmentCount(profile)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select dispatched_at from event where event_id = ?",
                                OffsetDateTime.class,
                                id))
                .isNotNull();
    }

    @Test
    void dispatcherRerun_doesNotDuplicateEnrollment_andDoesNotMissEvents() throws Exception {
        String name = createJourney(newEventName());
        List<String> profiles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String p = ulid();
            profiles.add(p);
            ingest(event(ulid(), name, p, clock.instant())).andExpect(status().isAccepted());
        }
        dispatcher.runOnce();
        dispatcher.runOnce();
        for (String p : profiles) {
            assertThat(enrollmentCount(p)).isEqualTo(1);
        }
    }

    @Test
    void newJourneyVersion_doesNotAffectInFlightEnrollment() throws Exception {
        String name = newEventName();
        String profile = enrollViaEvent(name);
        String eid = enrollmentId(profile);
        String jid =
                jdbc.queryForObject(
                        "select journey_id from enrollment_record where enrollment_id = ?",
                        String.class,
                        eid);
        Map<String, Object> v2 = journey(name, "PT5H");
        v2.put("journey_id", jid);
        postJson("/api/v1/journeys", "test-admin-key", v2).andExpect(status().isCreated());

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();
        assertThat(enrollmentStatus(eid)).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "select journey_version from enrollment_record where enrollment_id = ?",
                                Integer.class,
                                eid))
                .isEqualTo(1);
    }

    // ---------- 中斷重啟：不漏不重 ----------
    // 以資料庫 trigger 模擬「該步交易中途失敗」（整筆回滾），移除後重跑。

    private void installFailure(String table, String column, String value) {
        jdbc.execute(
                "create or replace function acc_fail() returns trigger as $$ begin"
                        + " raise exception 'injected'; end $$ language plpgsql");
        jdbc.execute(
                "create trigger acc_fail_trg before insert on "
                        + table
                        + " for each row when (NEW."
                        + column
                        + " = '"
                        + value
                        + "') execute function acc_fail()");
    }

    private void removeFailure(String table) {
        jdbc.execute("drop trigger if exists acc_fail_trg on " + table);
    }

    @Test
    void dispatcherInterrupted_thenRerun_createsExactlyOneEnrollment() throws Exception {
        String name = createJourney(newEventName());
        String profile = ulid();
        String id = ulid();
        ingest(event(id, name, profile, clock.instant())).andExpect(status().isAccepted());

        installFailure("enrollment_record", "profile_id", profile);
        try {
            try {
                dispatcher.runOnce();
            } catch (RuntimeException expected) {
                // 實作可拋出或吞掉；要求的是結果
            }
            assertThat(enrollmentCount(profile)).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select dispatched_at from event where event_id = ?",
                                    OffsetDateTime.class,
                                    id))
                    .as("失敗的派發不得標為已派發（否則漏）")
                    .isNull();
        } finally {
            removeFailure("enrollment_record");
        }

        dispatcher.runOnce();
        dispatcher.runOnce();
        assertThat(enrollmentCount(profile)).isEqualTo(1);
        assertThat(trace(enrollmentId(profile))).hasSize(2);
    }

    @Test
    void advanceInterrupted_thenRerun_completesWithExactlyOneOutboxAndContiguousTrace()
            throws Exception {
        String profile = enrollViaEvent(newEventName());
        String eid = enrollmentId(profile);
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        installFailure("effect_outbox", "enrollment_id", eid);
        try {
            try {
                advanceLoop.runOnce();
            } catch (RuntimeException expected) {
                // 同上
            }
            assertThat(enrollmentStatus(eid)).as("整步回滾，仍待喚醒").isEqualTo("WAITING");
            assertThat(outboxCount(eid)).isZero();
            assertThat(trace(eid)).hasSize(2);
        } finally {
            removeFailure("effect_outbox");
        }

        advanceLoop.runOnce();
        advanceLoop.runOnce();
        assertThat(enrollmentStatus(eid)).isEqualTo("COMPLETED");
        assertThat(outboxCount(eid)).isEqualTo(1);
        assertThat(seqs(trace(eid))).containsExactly(1L, 2L, 3L, 4L);
        assertThat(enrollmentCount(profile)).isEqualTo(1);
    }
}
