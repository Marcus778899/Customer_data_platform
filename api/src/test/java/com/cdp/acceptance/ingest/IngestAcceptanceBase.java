package com.cdp.acceptance.ingest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.engine.EngineTestSupport.MutableClock;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.json.JsonMapper;

/**
 * FIX-03a 驗收共用：重試參數給小值（上限 5 次、初始退避 10 秒、退避上限 30 秒），
 * 輪詢調成 1 小時，讓派發與推進只由 runOnce() 驅動。規則見 spec 07 §11、D-024、D-026。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "cdp.engine.dispatch.max-attempts=5",
            "cdp.engine.dispatch.backoff-initial=PT10S",
            "cdp.engine.dispatch.backoff-max=PT30S",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
abstract class IngestAcceptanceBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected EventDispatcher dispatcher;
    @Autowired protected AdvanceLoop advanceLoop;

    @BeforeEach
    void resetState() {
        clock.reset();
        // 先前測試殘留的未派發事件不可干擾本測試的批次
        jdbc.update(
                "update event set dispatched_at = received_at, dispatch_outcome = 'DISPATCHED'"
                        + " where dispatched_at is null");
    }

    // ---------- 資料建立 ----------

    protected static String ulid() {
        return EngineTestSupport.ulid();
    }

    protected static String newEventName() {
        return "evt_" + ulid().toLowerCase();
    }

    private static Map<String, Object> node(
            String id, String type, Map<String, Object> config, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    /** 事件 → 等待 1 小時 → 發送 → 結束 的直線旅程，回傳 journey_id。 */
    protected String createJourney(String eventName) throws Exception {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "fix03a");
        j.put("settings", Map.of());
        j.put("entry_node", "start_1");
        j.put(
                "nodes",
                List.of(
                        node("start_1", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node(
                                "src_1",
                                "EventSourceNode",
                                Map.of("event_name", eventName),
                                Map.of("next", "wait_1")),
                        node(
                                "wait_1",
                                "WaitDelayNode",
                                Map.of("duration", "PT1H"),
                                Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        String body =
                postJson("/api/v1/journeys", j, "test-admin-key")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get("journey_id").asString();
    }

    protected Map<String, Object> event(String eventId, String name, String profileId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", eventId);
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", name);
        e.put("occurred_at", clock.instant().minusSeconds(60).toString());
        e.put("identity", Map.of("profile_id", profileId));
        e.put("properties", Map.of("cart_id", "CART-1"));
        return e;
    }

    protected ResultActions ingest(Map<String, Object> event) throws Exception {
        return postJson("/api/v1/events", event, "test-ingest-key");
    }

    protected ResultActions postJson(String path, Object body, String key) throws Exception {
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + key)
                        .with(user("qa").roles("ADMIN", "INGEST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    /** 送入一筆事件並確認 202，回傳 event_id。 */
    protected String ingestOk(String name, String profileId) throws Exception {
        String id = ulid();
        ingest(event(id, name, profileId)).andExpect(status().isAccepted());
        return id;
    }

    // ---------- 觀察 ----------

    protected Map<String, Object> eventRow(String eventId) {
        return jdbc.queryForMap(
                "select dispatch_attempts, next_dispatch_at, last_dispatch_error,"
                        + " dispatch_outcome, dispatched_at from event where event_id = ?",
                eventId);
    }

    protected static int attempts(Map<String, Object> row) {
        return ((Number) row.get("dispatch_attempts")).intValue();
    }

    protected static Instant instantOf(Object ts) {
        if (ts == null) {
            return null;
        }
        if (ts instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        return ((OffsetDateTime) ts).toInstant();
    }

    protected int enrollmentCount(String profileId, String journeyId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where profile_id = ? and journey_id = ?",
                Integer.class,
                profileId,
                journeyId);
    }

    protected int enrollmentCount(String profileId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where profile_id = ?",
                Integer.class,
                profileId);
    }
}
