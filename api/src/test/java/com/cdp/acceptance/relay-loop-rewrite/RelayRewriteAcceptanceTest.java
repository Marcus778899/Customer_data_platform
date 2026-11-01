package com.cdp.acceptance.relayloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.relayloop.RelayLoopTestSupport.Gate;
import com.cdp.acceptance.relayloop.RelayLoopTestSupport.ProgrammableFaultInjector;
import com.cdp.common.fault.FaultPoint;
import com.cdp.delivery.RabbitTopology;
import com.cdp.delivery.relay.OutboxRelay;
import com.cdp.engine.runtime.AdvanceLoop;
import com.cdp.ingest.EventDispatcher;

/**
 * relay-loop-rewrite 驗收（DESIGN-03「relay 與迴圈」、spec 07 §12「relay」）：
 * 兩處中斷恰好一則、lease 過期重發恰好一則、遲到 confirm 不覆寫、3 倍批量一個 tick 內清空、
 * consumer 遇 outbox CANCELLED → delivery_task CANCELLED。
 *
 * <p>設定名稱（依既有 OUTBOX_RELAY_RETRY_DELAY → cdp.relay.retry-delay 的對應慣例）：
 * cdp.relay.confirm-timeout、cdp.relay.lease。時間用可控 Clock，lease 相對於 Clock。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "cdp.engine.batch-size=5",
            "cdp.relay.batch-size=5",
            "cdp.relay.confirm-timeout=PT2S",
            "cdp.relay.lease=PT10S",
            "cdp.relay.retry-delay=PT30S",
            "spring.task.scheduling.pool.size=4"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RelayLoopTestSupport.class)
class RelayRewriteAcceptanceTest extends RelayLoopBase {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    @Autowired EventDispatcher dispatcher;
    @Autowired AdvanceLoop advanceLoop;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired ProgrammableFaultInjector faults;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void reset() {
        faults.reset();
        clock.reset();
        // 前一個測試遺留的待推列不得影響本測試
        jdbc.update("update effect_outbox set status = 'PUBLISHED' where status = 'PENDING'");
    }

    @AfterEach
    void cleanup() {
        faults.reset();
        pool.shutdownNow();
    }

    // ---------- helpers ----------

    private record Scheduled(String enrollmentId, String key) {}

    /** 建旅程 → 事件 → 派發 → 快轉 → 喚醒；回傳 outbox 中等待推送的那一列。 */
    private Scheduled scheduleSend() throws Exception {
        String eventName = createJourney("PT1H");
        String profile = postEvent(eventName);
        dispatcher.runOnce();
        String eid = enrollmentOf(profile);
        assertThat(eid).as("事件應已派發建出 Enrollment").isNotNull();
        clock.advance(HOUR_PLUS);
        advanceLoop.runOnce();
        String key = outboxKeyOf(eid);
        assertThat(key).as("到期後應寫入 outbox").isNotNull();
        return new Scheduled(eid, key);
    }

    /** relay 被注入的故障打斷時，不論例外是否傳出 runOnce，測試都只看資料庫結果。 */
    private void runRelayIgnoringFaults() {
        try {
            relay.runOnce();
        } catch (RuntimeException ignored) {
            // 注入的中斷
        }
    }

    private long queueDepth() {
        var info = amqpAdmin.getQueueInfo(RabbitTopology.SEND_MESSAGE_QUEUE);
        return info == null ? -1L : info.getMessageCount();
    }

    private void assertSentExactlyOnce(Scheduled s) throws Exception {
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        await().atMost(WAIT).until(() -> queueDepth() == 0);
        Thread.sleep(1500);
        assertThat(mockCount(s.key())).as("恰好一則").isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from delivery_task where idempotency_key = ?",
                                Integer.class,
                                s.key()))
                .isEqualTo(1);
    }

    private static Binding sendMessageBinding() {
        return new Binding(
                RabbitTopology.SEND_MESSAGE_QUEUE,
                DestinationType.QUEUE,
                RabbitTopology.EXCHANGE,
                RabbitTopology.SEND_MESSAGE_ROUTING_KEY,
                null);
    }

    // ---------- 沿用的兩處中斷：恰好一則 ----------

    @Test
    void interruptBeforePublish_thenRestart_exactlyOnce() throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.RELAY_BEFORE_PUBLISH, s.key());

        runRelayIgnoringFaults();

        assertThat(outboxStatus(s.key())).as("未推送，維持 PENDING").isEqualTo("PENDING");
        assertThat(publishCount(s.key())).isZero();
        assertThat(taskStatus(s.key())).isNull();
        assertThat(mockCount(s.key())).isZero();

        // 重啟後（lease 未過期前不被搶；過期後重搶重推）
        faults.disarm(FaultPoint.RELAY_BEFORE_PUBLISH, s.key());
        clock.advance(LEASE.plusSeconds(1));
        relay.runOnce();

        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(publishCount(s.key())).isEqualTo(1);
        assertSentExactlyOnce(s);
    }

    @Test
    void interruptAfterConfirm_thenRestart_exactlyOnce() throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());

        runRelayIgnoringFaults();

        // 訊息已進 broker 並被 consumer 處理，但列尚未標 PUBLISHED
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));
        assertThat(outboxStatus(s.key())).isEqualTo("PENDING");
        assertThat(publishCount(s.key())).isZero();

        faults.disarm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());
        clock.advance(LEASE.plusSeconds(1));
        relay.runOnce();

        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(publishCount(s.key())).isEqualTo(1);
        assertSentExactlyOnce(s);
    }

    // ---------- lease 過期重發恰好一則 ----------

    @Test
    void leaseExpiredResend_isAbsorbedByConsumer_exactlyOne_publishCountOne() throws Exception {
        Scheduled s = scheduleSend();
        faults.arm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());
        runRelayIgnoringFaults();
        await().atMost(WAIT).until(() -> "SENT".equals(taskStatus(s.key())));

        // lease 未過期：另一輪 relay 不得重搶
        faults.disarm(FaultPoint.RELAY_AFTER_CONFIRM, s.key());
        relay.runOnce();
        assertThat(outboxStatus(s.key())).as("lease 內不重搶").isEqualTo("PENDING");
        assertThat(publishCount(s.key())).isZero();

        // 快轉超過 lease：重搶並重發第二則訊息，由 consumer 冪等吸收
        clock.advance(LEASE.plusSeconds(1));
        relay.runOnce();

        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(publishCount(s.key())).as("只在標 PUBLISHED 時遞增").isEqualTo(1);
        assertSentExactlyOnce(s);
    }

    // ---------- 遲到 confirm 不覆寫 ----------

    @Test
    void lateConfirm_afterOtherRoundPublished_doesNotOverwrite_publishCountOne() throws Exception {
        Scheduled s = scheduleSend();
        Gate gate = faults.blockOnce(FaultPoint.RELAY_BEFORE_WRITEBACK, s.key());

        Future<?> slow = pool.submit(this::runRelayIgnoringFaults);
        assertThat(gate.awaitArrived(WAIT)).as("第一輪已 confirm、卡在回寫前").isTrue();

        // lease 過期後另一輪重搶、發送、標 PUBLISHED
        clock.advance(LEASE.plusSeconds(1));
        relay.runOnce();
        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(publishCount(s.key())).isEqualTo(1);

        // 第一輪的遲到回寫：條件（status=PENDING 且 available_at = 自己的 lease）不成立，不得覆寫
        gate.release();
        slow.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outboxStatus(s.key())).isEqualTo("PUBLISHED");
        assertThat(publishCount(s.key())).as("遲到 confirm 不得再加一").isEqualTo(1);
        assertSentExactlyOnce(s);
    }

    @Test
    void lateConfirm_afterOtherRoundRescheduled_isFenced_doesNotMarkPublished() throws Exception {
        Scheduled s = scheduleSend();
        Gate gate = faults.blockOnce(FaultPoint.RELAY_BEFORE_WRITEBACK, s.key());

        Future<?> slow = pool.submit(this::runRelayIgnoringFaults);
        assertThat(gate.awaitArrived(WAIT)).isTrue();

        // 第二輪重搶後發佈失敗（沒有 binding → returned）→ 改排 now + retry-delay
        clock.advance(LEASE.plusSeconds(1));
        var expectedNext = clock.instant().plus(RETRY_DELAY);
        amqpAdmin.removeBinding(sendMessageBinding());
        try {
            relay.runOnce();
        } finally {
            amqpAdmin.declareBinding(sendMessageBinding());
        }
        assertThat(outboxStatus(s.key())).isEqualTo("PENDING");
        assertThat(availableAt(s.key())).isEqualTo(expectedNext);

        // 第一輪的遲到成功回寫：available_at 已不是它的 lease，必須被擋下
        gate.release();
        slow.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(outboxStatus(s.key())).as("fencing：不得被舊 lease 標成 PUBLISHED").isEqualTo("PENDING");
        assertThat(publishCount(s.key())).isZero();
        assertThat(availableAt(s.key())).as("也不得被舊結果改寫排程").isEqualTo(expectedNext);
    }

    // ---------- 3 倍批量到期一個 tick 內清空 ----------

    @Test
    void threeTimesBatchDue_drainedWithinOneTickPerLoop() throws Exception {
        String eventName = createJourney("PT1H");
        List<String> profiles = new ArrayList<>();
        for (int i = 0; i < 15; i++) { // batch-size = 5 的 3 倍
            profiles.add(postEvent(eventName));
        }

        dispatcher.tick();
        for (String p : profiles) {
            assertThat(enrollmentOf(p)).as("EventDispatcher 一個 tick 內滿批續做，派發完 15 個事件").isNotNull();
        }

        clock.advance(HOUR_PLUS);
        advanceLoop.tick();
        List<String> keys = new ArrayList<>();
        for (String p : profiles) {
            String key = outboxKeyOf(enrollmentOf(p));
            assertThat(key).as("AdvanceLoop 一個 tick 內清空 15 筆到期").isNotNull();
            keys.add(key);
        }

        relay.tick();
        for (String k : keys) {
            assertThat(outboxStatus(k)).as("relay 一個 tick 內清空 15 列").isEqualTo("PUBLISHED");
            assertThat(publishCount(k)).isEqualTo(1);
        }
    }

    // ---------- consumer 遇 outbox CANCELLED ----------

    @Test
    void consumer_outboxCancelled_marksDeliveryTaskCancelled_noMessageSent() throws Exception {
        Scheduled s = scheduleSend();
        jdbc.update("update effect_outbox set status = 'CANCELLED' where idempotency_key = ?", s.key());

        // 模擬取消前已在 MQ 的訊息
        rabbit.convertAndSend(
                RabbitTopology.EXCHANGE, RabbitTopology.SEND_MESSAGE_ROUTING_KEY, s.key());

        await().atMost(WAIT).until(() -> "CANCELLED".equals(taskStatus(s.key())));
        await().atMost(WAIT).until(() -> queueDepth() == 0);
        Thread.sleep(500);
        assertThat(mockCount(s.key())).as("已取消不得發送").isZero();
        assertThat(outboxStatus(s.key())).isEqualTo("CANCELLED");
    }
}
