package com.cdp.acceptance.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import com.cdp.acceptance.delivery.DeliveryTestSupport.MutableClock;
import com.cdp.acceptance.delivery.DeliveryTestSupport.ProgrammableFaultInjector;
import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.common.fault.FaultPoint;
import com.cdp.delivery.RabbitTopology;
import com.cdp.delivery.relay.OutboxRelay;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.json.JsonMapper;

/**
 * BE-02 驗收：outbox → RabbitMQ → 冪等 consumer → Mock。只用 HTTP、runOnce()、可控 Clock、FaultInjector 與 SQL／AMQP
 * 觀察。輪詢間隔調成 1 小時，relay 只由 runOnce() 驅動；consumer（@RabbitListener）照常自動啟動。
 */
@SpringBootTest(
        properties = {"cdp.engine.poll-interval=PT1H", "cdp.relay.poll-interval=PT1H", "cdp.relay.lease=PT10S"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeliveryTestSupport.class)
class DeliveryPipelineAcceptanceTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired ProgrammableFaultInjector faults;
    @Autowired EventDispatcher dispatcher;
    @Autowired AdvanceLoop advanceLoop;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;

    private final Set<String> deadLettered = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- helpers ----------

    private ResultActions postJson(String path, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder b =
                post(path)
                        .header("Authorization", "Bearer " + key)
                        .with(user("qa").roles("ADMIN", "INGEST"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body));
        return mvc.perform(b);
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

    private Map<String, Object> journey(String eventName) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "棄單挽回");
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
        return j;
    }

    /** 一次旅程的結果：旅程、Enrollment 與 outbox 冪等鍵。 */
    private record Scheduled(String journeyId, String enrollmentId, String key) {}

    /** 建旅程 → 攝取事件 → 派發 → 快轉 → 喚醒；回傳 outbox 中等待推送的那一列。 */
    private Scheduled scheduleSend() throws Exception {
        String eventName = "evt_" + ulid().toLowerCase();
        String body =
                postJson("/api/v1/journeys", "test-admin-key", journey(eventName))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String journeyId = json.readTree(body).get("journey_id").asString();

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
        dispatcher.runOnce();

        String eid =
                jdbc.queryForObject(
                        "select enrollment_id from enrollment_record where profile_id = ?",
                        String.class,
                        profile);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();
        String key =
                jdbc.queryForObject(
                        "select idempotency_key from effect_outbox where enrollment_id = ?",
                        String.class,
                        eid);
        return new Scheduled(journeyId, eid, key);
    }

    private static String ulid() {
        return EngineTestSupport.ulid();
    }

    private String outboxStatus(String key) {
        return jdbc.queryForObject(
                "select status from effect_outbox where idempotency_key = ?", String.class, key);
    }

    private int publishCount(String key) {
        return jdbc.queryForObject(
                "select publish_count from effect_outbox where idempotency_key = ?",
                Integer.class,
                key);
    }

    private String taskStatus(String key) {
        List<String> r =
                jdbc.queryForList(
                        "select status from delivery_task where idempotency_key = ?",
                        String.class,
                        key);
        return r.isEmpty() ? null : r.get(0);
    }

    private int mockCount(String key) {
        return jdbc.queryForObject(
                "select count(*) from mock_channel_message where idempotency_key = ?",
                Integer.class,
                key);
    }

    private int historyLength(String eid) {
        return jdbc.queryForObject(
                "select jsonb_array_length(delivery_history) from enrollment_record"
                        + " where enrollment_id = ?",
                Integer.class,
                eid);
    }

    private long queueDepth() {
        var info = amqpAdmin.getQueueInfo(RabbitTopology.SEND_MESSAGE_QUEUE);
        return info == null ? -1L : info.getMessageCount();
    }

    /** 等 queue 清空並多留一點時間讓最後一則處理完（單一 consumer，順序處理）。 */
    private void settle() throws InterruptedException {
        await().atMost(WAIT).until(() -> queueDepth() == 0);
        Thread.sleep(1500);
    }

    private void pollDlq() {
        Message m;
        while ((m = rabbit.receive(RabbitTopology.SEND_MESSAGE_DLQ, 200)) != null) {
            deadLettered.add(new String(m.getBody(), StandardCharsets.UTF_8));
        }
    }

    private void redeliver(String key) {
        rabbit.convertAndSend(
                RabbitTopology.EXCHANGE, RabbitTopology.SEND_MESSAGE_ROUTING_KEY, key);
    }

    private void insertRawOutbox(String key, String effectType, String payloadJson) {
        jdbc.update(
                "insert into effect_outbox(idempotency_key, enrollment_id, journey_id,"
                        + " journey_version, node_id, visit_seq, effect_index, effect_type, payload,"
                        + " status, available_at, publish_count, created_at)"
                        + " values (?, ?, ?, 1, 'send_1', 3, 0, ?, ?::jsonb, 'PENDING', ?, 0, ?)",
                key,
                "enr_" + key,
                "jrn_" + key,
                effectType,
                payloadJson,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
    }

    private static String validPayload(String key) {
        return "{\"idempotency_key\":\""
                + key
                + "\",\"template_id\":\"t\",\"channel_id\":\"c\",\"variables\":{}}";
    }

    // ---------- 驗收 1：端到端 ----------

    @Test
    void topology_queueAndDlqAreDeclared() {
        assertThat(amqpAdmin.getQueueProperties(RabbitTopology.SEND_MESSAGE_QUEUE)).isNotNull();
        assertThat(amqpAdmin.getQueueProperties(RabbitTopology.SEND_MESSAGE_DLQ)).isNotNull();
    }

    @Test
    void endToEnd_eventWaitWake_relayPublishes_consumerSendsViaMock() throws Exception {
        Scheduled s = scheduleSend();
        assertThat(outboxStatus(s.key())).isEqualTo("PENDING");
        assertThat(taskStatus(s.key())).as("relay 之前不會有發送任務").isNull();

        assertThat(relay.runOnce()).isGreaterThanOrEqualTo(1);

        // confirm 後 outbox 標 PUBLISHED（同步，runOnce 回傳時已完成）
        Map<String, Object> ob =
                jdbc.queryForMap(
                        "select status, publish_count, published_at from effect_outbox"
                                + " where idempotency_key = ?",
                        s.key());
        assertThat(ob.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) ob.get("publish_count")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(ob.get("published_at")).isNotNull();

        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        assertThat(mockCount(s.key())).isEqualTo(1);

        Map<String, Object> t =
                jdbc.queryForMap("select * from delivery_task where idempotency_key = ?", s.key());
        assertThat(t).doesNotContainKey("tenant_id");
        assertThat(t.get("enrollment_id")).isEqualTo(s.enrollmentId());
        assertThat(t.get("journey_id")).isEqualTo(s.journeyId());
        assertThat(t.get("journey_version")).isEqualTo(1);
        assertThat(t.get("node_id")).isEqualTo("send_1");
        assertThat(t.get("template_id")).isEqualTo("tpl_a");
        assertThat(t.get("channel_id")).isEqualTo("ch_a");
        assertThat(t.get("channel_type")).isEqualTo("MOCK");
        assertThat(t.get("message_type")).isNull();
        assertThat(t.get("skip_reason")).isNull();
        assertThat(t.get("delivery_id")).isNotNull();
        assertThat(t.get("attempted_at")).isNotNull();
        long visitSeq =
                jdbc.queryForObject(
                        "select visit_seq from effect_outbox where idempotency_key = ?",
                        Long.class,
                        s.key());
        assertThat(((Number) t.get("visit_seq")).longValue()).isEqualTo(visitSeq);

        // delivery_history 追加恰好一筆
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select delivery_history->0->>'status' from enrollment_record"
                                        + " where enrollment_id = ?",
                                String.class,
                                s.enrollmentId()))
                .isEqualTo("SENT");

        // 再跑 relay 不會重推已 PUBLISHED 的列
        int before = publishCount(s.key());
        relay.runOnce();
        settle();
        assertThat(publishCount(s.key())).isEqualTo(before);
        assertThat(mockCount(s.key())).isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
    }

    @Test
    void relay_notDueRowsAreNotPublished() throws Exception {
        String key = "future-" + ulid();
        insertRawOutbox(key, "SendMessage", validPayload(key));
        jdbc.update(
                "update effect_outbox set available_at = ? where idempotency_key = ?",
                Timestamp.from(clock.instant().plus(Duration.ofHours(2))),
                key);
        relay.runOnce();
        settle();
        assertThat(outboxStatus(key)).isEqualTo("PENDING");
        assertThat(taskStatus(key)).isNull();
        assertThat(mockCount(key)).isZero();
    }

    @Test
    void relay_unknownEffectType_staysPending_notPublished() throws Exception {
        String key = "unknown-" + ulid();
        insertRawOutbox(key, "NoSuchEffect", "{\"idempotency_key\":\"" + key + "\"}");
        relay.runOnce();
        settle();
        assertThat(outboxStatus(key)).isEqualTo("PENDING");
        assertThat(publishCount(key)).isZero();
        assertThat(taskStatus(key)).isNull();
        pollDlq();
        assertThat(deadLettered).doesNotContain(key);
    }

    // ---------- 驗收 2：冪等 ----------

    @Test
    void relayInterruptedBeforePublish_thenRerun_sendsExactlyOnce() throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.RELAY_BEFORE_PUBLISH, s.key());
        try {
            try {
                relay.runOnce();
            } catch (RuntimeException expected) {
                // 實作可拋出或吞掉；要求的是結果
            }
            settle();
            assertThat(outboxStatus(s.key())).as("尚未推送，仍待推").isEqualTo("PENDING");
            assertThat(mockCount(s.key())).isZero();
        } finally {
            faults.disarm(FaultPoint.RELAY_BEFORE_PUBLISH, s.key());
        }

        clock.advance(Duration.ofSeconds(11));
        relay.runOnce();
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        relay.runOnce();
        settle();
        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(mockCount(s.key())).isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
    }

    @Test
    void relayInterruptedAfterConfirm_thenRestart_republishes_butMockSendsExactlyOnce()
            throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());
        try {
            try {
                relay.runOnce();
            } catch (RuntimeException expected) {
                // 同上
            }
            // broker 已收到並 confirm，consumer 會送出；但 outbox 尚未標記
            await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
            assertThat(outboxStatus(s.key())).isEqualTo("PENDING");
            assertThat(mockCount(s.key())).isEqualTo(1);
        } finally {
            faults.disarm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());
        }

        // 「重啟」：lease 過期後 relay 再取到同一列，重複推送
        clock.advance(Duration.ofSeconds(11));
        relay.runOnce();
        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        settle();

        assertThat(queueDepth()).isZero();
        assertThat(taskStatus(s.key())).isEqualTo("SENT");
        assertThat(mockCount(s.key())).as("重複投遞只送一次").isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).as("history 不重複追加").isEqualTo(1);
    }

    @Test
    void consumerCommittedButNotAcked_thenRedelivered_sendsExactlyOnce() throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.CONSUMER_AFTER_COMMIT, s.key());
        try {
            relay.runOnce();
            // 交易已提交（Mock 已送出），ack 前中斷
            await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
            settle();
        } finally {
            faults.disarm(FaultPoint.CONSUMER_AFTER_COMMIT, s.key());
        }
        assertThat(mockCount(s.key())).isEqualTo(1);

        // 同一則再被投遞（未 ack 的重投，或 relay 重推）
        redeliver(s.key());
        redeliver(s.key());
        settle();

        assertThat(taskStatus(s.key())).isEqualTo("SENT");
        assertThat(mockCount(s.key())).isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
    }

    @Test
    void duplicateDelivery_ofSameMessage_sendsExactlyOnce() throws Exception {
        Scheduled s = scheduleSend();
        relay.runOnce();
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        for (int i = 0; i < 3; i++) {
            redeliver(s.key());
        }
        settle();
        assertThat(mockCount(s.key())).isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from mock_channel_message where enrollment_id = ?",
                                Integer.class,
                                s.enrollmentId()))
                .isEqualTo(1);
    }

    // ---------- 驗收 3：KillSwitch／DLQ／DB 不可用 ----------

    @Test
    void journeyNotActive_killSwitch_neverSends() throws Exception {
        Scheduled s = scheduleSend();
        jdbc.update(
                "update journey_definition set status = 'PAUSED' where journey_id = ?",
                s.journeyId());

        relay.runOnce();
        settle();

        assertThat(mockCount(s.key())).as("旅程非 ACTIVE 不得送出").isZero();
        assertThat(taskStatus(s.key())).isEqualTo("CANCELLED");
        assertThat(historyLength(s.enrollmentId())).isZero();
        assertThat(queueDepth()).as("訊息已處理，不留在 queue").isZero();
    }

    @Test
    void unparsablePayload_goesToDlq_andNeverSends() throws Exception {
        String missing = "bad-missing-" + ulid();
        String wrongType = "bad-type-" + ulid();
        insertRawOutbox(missing, "SendMessage", "{\"idempotency_key\":\"" + missing + "\"}");
        insertRawOutbox(
                wrongType,
                "SendMessage",
                "{\"idempotency_key\":\""
                        + wrongType
                        + "\",\"template_id\":5,\"channel_id\":[],\"variables\":\"x\"}");

        relay.runOnce();

        await().atMost(WAIT)
                .untilAsserted(
                        () -> {
                            pollDlq();
                            assertThat(deadLettered).contains(missing, wrongType);
                        });
        assertThat(mockCount(missing)).isZero();
        assertThat(mockCount(wrongType)).isZero();
        assertThat(taskStatus(missing)).isNotEqualTo("SENT");
        assertThat(taskStatus(wrongType)).isNotEqualTo("SENT");
        assertThat(queueDepth()).isZero();
    }

    @Test
    void databaseFailure_requeues_messageNotLost_thenSendsOnceAfterRecovery() throws Exception {
        Scheduled s = scheduleSend();
        jdbc.execute(
                "create or replace function acc_fail() returns trigger as $$ begin"
                        + " raise exception 'injected' using errcode = '40001'; end $$"
                        + " language plpgsql");
        jdbc.execute(
                "create trigger acc_fail_trg before insert on delivery_task"
                        + " for each row when (NEW.idempotency_key = '"
                        + s.key()
                        + "') execute function acc_fail()");
        try {
            relay.runOnce();
            assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
            Thread.sleep(2000);
            assertThat(mockCount(s.key())).isZero();
            assertThat(taskStatus(s.key())).isNull();
            pollDlq();
            assertThat(deadLettered).as("DB 故障不得進 DLQ").doesNotContain(s.key());
        } finally {
            jdbc.execute("drop trigger if exists acc_fail_trg on delivery_task");
        }

        // DB 恢復後，原訊息（重新排隊）被處理，沒有遺失
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        settle();
        assertThat(mockCount(s.key())).isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
        pollDlq();
        assertThat(deadLettered).doesNotContain(s.key());
    }

    @Test
    void nonTransientDbError_goesToDlq_notRequeuedForever() throws Exception {
        Scheduled s = scheduleSend();
        // 預設 errcode（P0001）：非暫時性錯誤，重試也不會好
        jdbc.execute(
                "create or replace function acc_fail_perm() returns trigger as $$ begin"
                        + " raise exception 'injected'; end $$ language plpgsql");
        jdbc.execute(
                "create trigger acc_fail_perm_trg before insert on delivery_task"
                        + " for each row when (NEW.idempotency_key = '"
                        + s.key()
                        + "') execute function acc_fail_perm()");
        try {
            relay.runOnce();
            await().atMost(WAIT)
                    .untilAsserted(
                            () -> {
                                pollDlq();
                                assertThat(deadLettered).contains(s.key());
                            });
            assertThat(mockCount(s.key())).isZero();
            assertThat(taskStatus(s.key())).isNotEqualTo("SENT");
            await().atMost(WAIT).until(() -> queueDepth() == 0);
        } finally {
            jdbc.execute("drop trigger if exists acc_fail_perm_trg on delivery_task");
        }
    }
}
