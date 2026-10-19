package com.cdp.acceptance.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.cdp.acceptance.delivery.DeliveryTestSupport.MutableClock;
import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.delivery.RabbitTopology;
import com.cdp.delivery.relay.OutboxRelay;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

import tools.jackson.databind.json.JsonMapper;

/**
 * FIX-03b 驗收：relay 失敗退避與 mandatory／confirm 檢查；consumer 暫時性 DB 例外重排前等待、不空轉。
 * 屬性：{@code cdp.relay.retry-delay}（PT45S，以可控 Clock 驗證）、{@code cdp.delivery.requeue-delay}（PT2S，實際時間）。
 * 連線池縮到 3、取連線逾時 250ms（HikariCP 下限），測試佔滿連線池即可穩定製造 {@code CannotCreateTransactionException}。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "cdp.relay.retry-delay=PT45S",
            "cdp.delivery.requeue-delay=PT2S",
            "spring.datasource.hikari.maximum-pool-size=3",
            "spring.datasource.hikari.minimum-idle=3",
            "spring.datasource.hikari.connection-timeout=250"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({DeliveryTestSupport.class, DeliveryResilienceAcceptanceTest.DeliveryCounter.class})
class DeliveryResilienceAcceptanceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(45);
    private static final int POOL_SIZE = 3;

    /** 以 @Primary 的 ContainerCustomizer 掛 advice，記錄每次 listener 被呼叫的實際時間（含失敗與重排）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class DeliveryCounter {
        static final List<Instant> ATTEMPTS = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        ContainerCustomizer<SimpleMessageListenerContainer> attemptCounter() {
            return container ->
                    container.setAdviceChain(
                            (MethodInterceptor)
                                    invocation -> {
                                        ATTEMPTS.add(Instant.now());
                                        return invocation.proceed();
                                    });
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired EventDispatcher dispatcher;
    @Autowired AdvanceLoop advanceLoop;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired RabbitMQContainer rabbitContainer;

    @BeforeEach
    void reset() {
        clock.reset();
        DeliveryCounter.ATTEMPTS.clear();
        // 前一個測試遺留的待推列不得影響本測試
        jdbc.update("update effect_outbox set status = 'PUBLISHED' where status = 'PENDING'");
    }

    // ---------- helpers（與 DeliveryPipelineAcceptanceTest 同一套做法） ----------

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

    private record Scheduled(String enrollmentId, String key) {}

    /** 建旅程 → 事件 → 派發 → 快轉 → 喚醒；回傳 outbox 中等待推送的那一列。 */
    private Scheduled scheduleSend() throws Exception {
        String eventName = "evt_" + ulid().toLowerCase();
        postJson("/api/v1/journeys", "test-admin-key", journey(eventName))
                .andExpect(status().isCreated());
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
        return new Scheduled(eid, key);
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

    private Instant availableAt(String key) {
        return jdbc.queryForObject(
                        "select available_at from effect_outbox where idempotency_key = ?",
                        Timestamp.class,
                        key)
                .toInstant();
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

    private boolean inDlq(String key) {
        Message m;
        boolean found = false;
        while ((m = rabbit.receive(RabbitTopology.SEND_MESSAGE_DLQ, 200)) != null) {
            found |= key.equals(new String(m.getBody(), StandardCharsets.UTF_8));
        }
        return found;
    }

    private void assertSentExactlyOnce(Scheduled s) throws Exception {
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        await().atMost(WAIT).until(() -> queueDepth() == 0);
        Thread.sleep(1500);
        assertThat(mockCount(s.key())).as("恰好一則").isEqualTo(1);
        assertThat(historyLength(s.enrollmentId())).isEqualTo(1);
    }

    private static Binding sendMessageBinding() {
        return new Binding(
                RabbitTopology.SEND_MESSAGE_QUEUE,
                DestinationType.QUEUE,
                RabbitTopology.EXCHANGE,
                RabbitTopology.SEND_MESSAGE_ROUTING_KEY,
                null);
    }

    private void insertRawOutbox(String key, String effectType, String payloadJson) {
        jdbc.update(
                "insert into effect_outbox(idempotency_key, tenant_id, enrollment_id, journey_id,"
                        + " journey_version, node_id, visit_seq, effect_index, effect_type, payload,"
                        + " status, available_at, publish_count, created_at)"
                        + " values (?, ?, ?, ?, 1, 'send_1', 3, 0, ?, ?::jsonb, 'PENDING', ?, 0, ?)",
                key,
                "01JTESTTENANT0000000000001",
                "enr_" + key,
                "jrn_" + key,
                effectType,
                payloadJson,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
    }

    // ---------- 驗收 1：relay ----------

    @Test
    void relay_brokerUnavailable_batchWaitsOnlyOnce_failedRowDelayed_staysPending()
            throws Exception {
        // 先暖機：建立並快取連線與 channel（暫停後新連線握手會卡很久，與本案無關）
        Scheduled warm = scheduleSend();
        relay.runOnce();
        assertSentExactlyOnce(warm);

        clock.reset();
        Scheduled a = scheduleSend();
        Scheduled b = scheduleSend();
        Scheduled c = scheduleSend();
        List<String> keys = List.of(a.key(), b.key(), c.key());
        Map<String, Instant> before = new LinkedHashMap<>();
        keys.forEach(k -> before.put(k, availableAt(k)));
        Instant now = clock.instant();

        var docker = DockerClientFactory.instance().client();
        String containerId = rabbitContainer.getContainerId();
        docker.pauseContainerCmd(containerId).exec();
        long elapsedMillis;
        try {
            long t0 = System.nanoTime();
            relay.runOnce();
            elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }

        // 三列都可推，舊行為每列各等一次逾時（>= 3 倍）；新行為首次失敗即結束批次
        assertThat(elapsedMillis).as("一批只等一次 confirm 逾時").isLessThan(9_000);
        for (String k : keys) {
            assertThat(outboxStatus(k)).as("未 confirm 不得標 PUBLISHED").isEqualTo("PENDING");
            assertThat(publishCount(k)).isZero();
        }
        List<String> delayed = new ArrayList<>();
        for (String k : keys) {
            Instant at = availableAt(k);
            if (at.equals(now.plus(RETRY_DELAY))) {
                delayed.add(k);
            } else {
                assertThat(at).as("未處理的列不變").isEqualTo(before.get(k));
            }
        }
        assertThat(delayed).as("失敗列 available_at = now + cdp.relay.retry-delay").isNotEmpty();

        // 退避期間不重推；超過 retry-delay 後恢復，三列各恰好一則
        relay.runOnce();
        for (String k : delayed) {
            assertThat(outboxStatus(k)).isEqualTo("PENDING");
            assertThat(publishCount(k)).isZero();
        }
        clock.advance(RETRY_DELAY.plusSeconds(1));
        for (int i = 0; i < 3; i++) {
            relay.runOnce();
        }
        for (Scheduled s : List.of(a, b, c)) {
            assertSentExactlyOnce(s);
            assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void relay_noBinding_returned_staysPending_notPublished_thenRecovers() throws Exception {
        Scheduled s = scheduleSend();
        Instant now = clock.instant();
        amqpAdmin.removeBinding(sendMessageBinding());
        try {
            relay.runOnce();

            assertThat(outboxStatus(s.key())).as("returned 視同失敗").isEqualTo("PENDING");
            assertThat(publishCount(s.key())).isZero();
            assertThat(availableAt(s.key())).isEqualTo(now.plus(RETRY_DELAY));
            assertThat(queueDepth()).isZero();
            assertThat(taskStatus(s.key())).isNull();
        } finally {
            amqpAdmin.declareBinding(sendMessageBinding());
        }

        // binding 恢復但仍在退避期：不推
        relay.runOnce();
        assertThat(outboxStatus(s.key())).isEqualTo("PENDING");
        assertThat(publishCount(s.key())).isZero();

        clock.advance(RETRY_DELAY.plusSeconds(1));
        relay.runOnce();
        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertSentExactlyOnce(s);
    }

    @Test
    void relay_unknownEffectType_isRescheduled_notStuckAtHead() {
        String key = "unknown-" + ulid();
        Instant now = clock.instant();
        insertRawOutbox(key, "NoSuchEffect", "{\"idempotency_key\":\"" + key + "\"}");

        relay.runOnce();

        assertThat(outboxStatus(key)).isEqualTo("PENDING");
        assertThat(publishCount(key)).isZero();
        assertThat(availableAt(key))
                .as("未知 effect_type 也延後，不占批首")
                .isEqualTo(now.plus(RETRY_DELAY));
    }

    // ---------- 驗收 2：consumer ----------

    /** 佔滿連線池，使 consumer 開交易時 CannotCreateTransactionException；回傳持有的連線。 */
    private List<Connection> exhaustPool() {
        List<Connection> held = new ArrayList<>();
        await().atMost(WAIT)
                .ignoreExceptions()
                .until(
                        () -> {
                            held.add(dataSource.getConnection());
                            return held.size() >= POOL_SIZE;
                        });
        return held;
    }

    private static void release(List<Connection> held) throws Exception {
        for (Connection c : held) {
            c.close();
        }
    }

    private Connection sideConnection;

    /** 不經連線池的獨立連線（池被佔滿時仍可查 DB）；網址與帳密取自池設定，不寫死。 */
    private Connection openOutOfPoolConnection() throws Exception {
        com.zaxxer.hikari.HikariDataSource hikari =
                dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        return java.sql.DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    private int countOutOfPool(String table, String key) throws Exception {
        try (java.sql.PreparedStatement ps =
                sideConnection.prepareStatement(
                        "select count(*) from " + table + " where idempotency_key = ?")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void assertPacedByRequeueDelay(List<Instant> attempts) {
        assertThat(attempts.size()).as("至少重排一次（未進 DLQ）").isGreaterThanOrEqualTo(2);
        for (int i = 1; i < attempts.size(); i++) {
            assertThat(Duration.between(attempts.get(i - 1), attempts.get(i)))
                    .as("第 %d 次與前一次相隔須 >= cdp.delivery.requeue-delay", i + 1)
                    .isGreaterThanOrEqualTo(Duration.ofMillis(1_500));
        }
    }

    @Test
    void consumer_cannotCreateTransaction_waitsRequeueDelay_noBusySpin_thenExactlyOnce()
            throws Exception {
        Scheduled s = scheduleSend();
        sideConnection = openOutOfPoolConnection();
        List<Connection> held = exhaustPool();
        List<Instant> attempts;
        try {
            rabbit.convertAndSend(
                    RabbitTopology.EXCHANGE, RabbitTopology.SEND_MESSAGE_ROUTING_KEY, s.key());
            Thread.sleep(7_000);
            attempts = new ArrayList<>(DeliveryCounter.ATTEMPTS);
            // 須在釋放連線前斷言：釋放後 consumer 隨時可能提交（FIX-09：原先放在 finally 之後有競態）。
            // 連線池已被佔滿，改用池外的獨立連線查詢。
            assertThat(countOutOfPool("delivery_task", s.key()))
                    .as("DB 不可用期間不得有任務")
                    .isZero();
            assertThat(countOutOfPool("mock_channel_message", s.key()))
                    .as("DB 不可用期間不得發送")
                    .isZero();
        } finally {
            release(held);
            sideConnection.close();
        }

        // 7 秒、delay 2 秒：約 4 次；立即重排（舊行為）每 ~250ms 一次，會超過 20 次
        assertThat(attempts.size()).as("斷線期間不空轉").isLessThanOrEqualTo(6);
        assertPacedByRequeueDelay(attempts);
        assertThat(inDlq(s.key())).as("暫時性例外不得進 DLQ").isFalse();

        assertSentExactlyOnce(s);
        assertThat(inDlq(s.key())).isFalse();
    }

    @Test
    void consumer_serializationFailure40001_waitsRequeueDelay_noBusySpin_thenExactlyOnce()
            throws Exception {
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
        List<Instant> attempts;
        try {
            relay.runOnce();
            assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
            Thread.sleep(7_000);
            attempts = new ArrayList<>(DeliveryCounter.ATTEMPTS);
            assertThat(mockCount(s.key())).isZero();
            assertThat(inDlq(s.key())).as("暫時性例外不得進 DLQ").isFalse();
        } finally {
            jdbc.execute("drop trigger if exists acc_fail_trg on delivery_task");
        }

        assertThat(attempts.size()).as("故障期間不空轉").isLessThanOrEqualTo(6);
        assertPacedByRequeueDelay(attempts);

        assertSentExactlyOnce(s);
    }
}
