package com.cdp.acceptance.relayloop;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.acceptance.relayloop.RelayLoopTestSupport.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/** 共用 helper：建旅程（等待 waitIso 後發訊息）、送事件、查 outbox／delivery_task／Mock。 */
abstract class RelayLoopBase {

    static final Duration HOUR_PLUS = Duration.ofHours(1).plusSeconds(1);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    static String ulid() {
        return EngineTestSupport.ulid();
    }

    ResultActions postJson(String path, String key, Object body) throws Exception {
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + key)
                        .with(user("qa").roles("ADMIN", "INGEST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
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

    /** 建立「事件 → 等待 waitIso → 發訊息」旅程，回傳事件名稱。 */
    String createJourney(String waitIso) throws Exception {
        String eventName = "evt_" + ulid().toLowerCase();
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "relay-loop");
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
                                Map.of("duration", waitIso),
                                Map.of("next", "send_1")),
                        node(
                                "send_1",
                                "SendMessageNode",
                                Map.of("template_id", "tpl_a", "channel_id", "ch_a"),
                                Map.of("sent", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
        postJson("/api/v1/journeys", "test-admin-key", j).andExpect(status().isCreated());
        return eventName;
    }

    /** 送一個事件，回傳 profile id。 */
    String postEvent(String eventName) throws Exception {
        String profile = ulid();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", ulid());
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", eventName);
        e.put("occurred_at", clock.instant().minusSeconds(60).toString());
        e.put("identity", Map.of("profile_id", profile));
        e.put("properties", Map.of("cart_id", "CART-1"));
        postJson("/api/v1/events", "test-ingest-key", e).andExpect(status().isAccepted());
        return profile;
    }

    String enrollmentOf(String profile) {
        List<String> r =
                jdbc.queryForList(
                        "select enrollment_id from enrollment_record where profile_id = ?",
                        String.class,
                        profile);
        return r.isEmpty() ? null : r.get(0);
    }

    String outboxKeyOf(String enrollmentId) {
        List<String> r =
                jdbc.queryForList(
                        "select idempotency_key from effect_outbox where enrollment_id = ?",
                        String.class,
                        enrollmentId);
        return r.isEmpty() ? null : r.get(0);
    }

    String outboxStatus(String key) {
        return jdbc.queryForObject(
                "select status from effect_outbox where idempotency_key = ?", String.class, key);
    }

    int publishCount(String key) {
        return jdbc.queryForObject(
                "select publish_count from effect_outbox where idempotency_key = ?",
                Integer.class,
                key);
    }

    Instant availableAt(String key) {
        return jdbc.queryForObject(
                        "select available_at from effect_outbox where idempotency_key = ?",
                        Timestamp.class,
                        key)
                .toInstant();
    }

    String taskStatus(String key) {
        List<String> r =
                jdbc.queryForList(
                        "select status from delivery_task where idempotency_key = ?",
                        String.class,
                        key);
        return r.isEmpty() ? null : r.get(0);
    }

    int mockCount(String key) {
        return jdbc.queryForObject(
                "select count(*) from mock_channel_message where idempotency_key = ?",
                Integer.class,
                key);
    }
}
