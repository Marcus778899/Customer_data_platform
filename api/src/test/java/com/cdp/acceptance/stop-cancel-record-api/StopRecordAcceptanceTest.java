package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * uat F-29、journeys.md §3.5、spec 05 §5：停止旅程時，未送出的訊息在取消當下留一筆
 * （delivery_task：CANCELLED＋JOURNEY_STOPPED），軌跡 API 查得到；含停止前已走完、訊息仍待送的旅程進度。
 */
class StopRecordAcceptanceTest extends StopCancelBase {

    /** 進到訊息已排入 outbox（relay 未跑）的狀態。 */
    private String enrollToPendingSend(String event) throws Exception {
        String p = ulid();
        ingest(event, p, props());
        String e = enrollmentOf(p);
        advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(outboxStatuses(e)).as("訊息已排入、尚未送出").containsExactly("PENDING");
        return e;
    }

    @Test
    void f29_stopWhileMessagePendingInOutbox_finishedEnrollment_hasCancelledRecord() throws Exception {
        String ev = "evt_f29_" + uniq();
        String j = publish(sendThenEnd(Map.of(), ev));
        String e = enrollToPendingSend(ev);
        assertThat(status(e)).as("旅程進度已走完").isNotIn("PENDING", "ACTIVE", "WAITING", "SUSPENDED");
        assertThat(deliveries(e)).as("停止前沒有取消紀錄").noneMatch(d -> "CANCELLED".equals(d.path("status").asString()));

        stop(j, "活動取消");

        assertThat(outboxStatuses(e)).containsExactly("CANCELLED");
        JsonNode items = deliveries(e);
        assertThat(items).hasSize(1);
        assertCancelled(items.get(0), "JOURNEY_STOPPED", clock.instant());
        assertThat(items.get(0).path("message_type").isNull()).isTrue();

        // 進度詳情的 delivery_history 也看得到
        JsonNode hist = deliveryHistory(e);
        assertThat(hist).hasSize(1);
        assertCancelled(hist.get(0), "JOURNEY_STOPPED", clock.instant());

        // 紀錄就在 delivery_task：CANCELLED＋cancel_reason
        Map<String, Object> row = jdbc.queryForMap(
                "select status, cancel_reason, channel_type, message_type, skip_reason, delivery_id"
                        + " from delivery_task where enrollment_id = ?",
                e);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancel_reason")).isEqualTo("JOURNEY_STOPPED");
        assertThat(row.get("channel_type")).isNotNull();
        assertThat(row.get("message_type")).isNull();
        assertThat(row.get("skip_reason")).isNull();
        assertThat(row.get("delivery_id")).isNull();

        // 紀錄的 key 與 outbox 一致
        String outboxKey =
                jdbc.queryForObject("select idempotency_key from effect_outbox where enrollment_id = ?", String.class, e);
        assertThat(items.get(0).path("idempotency_key").asString()).isEqualTo(outboxKey);

        // 取消的訊息不會被送出，也不會被改寫成其他狀態
        relay.runOnce();
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject(
                        "select count(*) from mock_channel_message where enrollment_id = ?", Integer.class, e))
                .isZero();
        assertThat(deliveries(e)).hasSize(1);
        assertCancelled(deliveries(e).get(0), "JOURNEY_STOPPED", clock.instant());
    }

    @Test
    void stop_inProgressEnrollments_eachGetsExactlyOneRecord_andSweeperAddsNone() throws Exception {
        String ev = "evt_f29b_" + uniq();
        String j = publish(sendThenWait(Map.of(), ev));
        String a = enrollToPendingSend(ev);
        String b = enrollToPendingSend(ev);
        // C：仍在 wait_1，還沒排入訊息
        String pc = ulid();
        ingest(ev, pc, props());
        String c = enrollmentOf(pc);
        assertThat(outboxStatuses(c)).isEmpty();

        stop(j, "停止測試");

        for (String e : new String[] {a, b}) {
            JsonNode items = deliveries(e);
            assertThat(items).as(e).hasSize(1);
            assertCancelled(items.get(0), "JOURNEY_STOPPED", clock.instant());
        }
        assertThat(deliveries(c)).as("沒有排入的訊息就沒有取消紀錄").isEmpty();

        sweepUntilDone(j);
        for (String e : new String[] {a, b}) {
            assertThat(status(e)).isEqualTo("ABORTED");
            JsonNode items = deliveries(e);
            assertThat(items).as("背景清理不重複寫紀錄").hasSize(1);
            assertCancelled(items.get(0), "JOURNEY_STOPPED", clock.instant());
        }
        assertThat(deliveries(c)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from delivery_task where enrollment_id in (?, ?, ?)", Integer.class, a, b, c))
                .isEqualTo(2);
    }

    @Test
    void stop_isIdempotent_doesNotDuplicateOrRewriteRecord() throws Exception {
        String ev = "evt_f29c_" + uniq();
        String j = publish(sendThenEnd(Map.of(), ev));
        String e = enrollToPendingSend(ev);
        stop(j, "第一次");
        Instant firstAt = clock.instant();
        clock.advance(Duration.ofMinutes(5));
        stop(j, "第二次");

        JsonNode items = deliveries(e);
        assertThat(items).hasSize(1);
        assertCancelled(items.get(0), "JOURNEY_STOPPED", firstAt);
    }

    @Test
    void stop_doesNotOverwriteMessageAlreadySent() throws Exception {
        String ev = "evt_f29d_" + uniq();
        String j = publish(sendThenWait(Map.of(), ev));
        String sentEnrollment = enrollToPendingSend(ev);
        relay.runOnce();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "select count(*) from delivery_task where enrollment_id = ? and status = 'SENT'",
                                Integer.class,
                                sentEnrollment))
                        .isEqualTo(1));
        // 另一個旅程進度的訊息仍待送
        String pendingEnrollment = enrollToPendingSend(ev);

        stop(j, "停止");

        JsonNode sent = deliveries(sentEnrollment);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("status").asString()).isEqualTo("SENT");
        assertThat(sent.get(0).path("cancel_reason").isNull()).as("已送出的紀錄 cancel_reason 為 null").isTrue();
        assertThat(sent.get(0).path("delivery_id").isNull()).isFalse();
        JsonNode pending = deliveries(pendingEnrollment);
        assertThat(pending).hasSize(1);
        assertCancelled(pending.get(0), "JOURNEY_STOPPED", clock.instant());
    }

    @Test
    void killSwitch_consumerBlocksPublishedMessage_recordsCancelledWithJourneyStopped() throws Exception {
        String ev = "evt_f29e_" + uniq();
        String j = publish(sendThenWait(Map.of(), ev));
        String e = enrollToPendingSend(ev);
        // 旅程非 ACTIVE（比照 DeliveryPipelineAcceptanceTest 的 KillSwitch 作法），relay 已推送後由 consumer 擋下
        jdbc.update("update journey_definition set status = 'PAUSED' where journey_id = ?", j);

        relay.runOnce();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "select count(*) from delivery_task where enrollment_id = ? and status = 'CANCELLED'",
                                Integer.class,
                                e))
                        .isEqualTo(1));
        assertThat(jdbc.queryForObject(
                        "select count(*) from mock_channel_message where enrollment_id = ?", Integer.class, e))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select cancel_reason from delivery_task where enrollment_id = ?", String.class, e))
                .isEqualTo("JOURNEY_STOPPED");
        JsonNode items = deliveries(e);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(items.get(0).path("cancel_reason").asString()).isEqualTo("JOURNEY_STOPPED");
    }
}
