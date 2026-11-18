package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * spec 02 §3、journey-engine.md §2 CancelReason：goal／global_exit 使旅程進度中途結束，未送出的訊息
 * 取消並留一筆 CANCELLED＋ENROLLMENT_ENDED；自然走完不取消；停止不改寫已記的原因。
 */
class EnrollmentEndedAcceptanceTest extends StopCancelBase {

    private static Map<String, Object> goalSettings(String event) {
        return Map.of(
                "goal",
                Map.of("goal_id", "purchase", "condition", Map.of("type", "event", "event", event), "window", "P7D"));
    }

    private String enrollToPendingSend(String event) throws Exception {
        String p = ulid();
        ingest(event, p, props());
        return pendingEnrollmentOf(p);
    }

    private String pendingEnrollmentOf(String profile) {
        String e = enrollmentOf(profile);
        advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(status(e)).isEqualTo("WAITING");
        assertThat(outboxStatuses(e)).containsExactly("PENDING");
        return e;
    }

    @Test
    void goalMet_whileMessagePending_recordsCancelledEnrollmentEnded() throws Exception {
        String entry = "evt_ee_in_" + uniq();
        String order = "evt_ee_order_" + uniq();
        String j = publish(sendThenWait(goalSettings(order), entry));
        String winner = ulid();
        ingest(entry, winner, props());
        String ew = pendingEnrollmentOf(winner);
        String bystander = enrollToPendingSend(entry);

        advance(Duration.ofMinutes(1));
        ingest(order, winner, props());

        assertThat(status(ew)).isEqualTo("GOAL_MET");
        assertThat(outboxStatuses(ew)).containsExactly("CANCELLED");
        JsonNode items = deliveries(ew);
        assertThat(items).hasSize(1);
        assertCancelled(items.get(0), "ENROLLMENT_ENDED", clock.instant());
        JsonNode hist = deliveryHistory(ew);
        assertThat(hist).hasSize(1);
        assertCancelled(hist.get(0), "ENROLLMENT_ENDED", clock.instant());
        assertThat(jdbc.queryForObject(
                        "select cancel_reason from delivery_task where enrollment_id = ?", String.class, ew))
                .isEqualTo("ENROLLMENT_ENDED");

        // 別人不受影響
        assertThat(outboxStatuses(bystander)).containsExactly("PENDING");
        assertThat(deliveries(bystander)).noneMatch(d -> "CANCELLED".equals(d.path("status").asString()));

        // 之後停止旅程：已結束者的紀錄與原因不變，仍只有一筆；旅程中的別人記 JOURNEY_STOPPED
        Duration later = Duration.ofMinutes(10);
        Instant cancelledAt = clock.instant();
        clock.advance(later);
        stop(j, "之後停止");
        assertThat(deliveries(ew)).hasSize(1);
        assertCancelled(deliveries(ew).get(0), "ENROLLMENT_ENDED", cancelledAt);
        assertThat(deliveries(bystander)).hasSize(1);
        assertCancelled(deliveries(bystander).get(0), "JOURNEY_STOPPED", clock.instant());
        sweepUntilDone(j);
        assertThat(deliveries(ew)).hasSize(1);
        assertThat(deliveries(bystander)).hasSize(1);
    }

    @Test
    void globalExit_eventCondition_whileMessagePending_recordsCancelledEnrollmentEnded() throws Exception {
        String entry = "evt_eg_in_" + uniq();
        String bye = "evt_eg_bye_" + uniq();
        publish(sendThenWait(
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(Map.of("type", "event", "event", bye, "since", "ENROLLMENT")),
                                "on_match",
                                "EXITED")),
                entry));
        String p = ulid();
        ingest(entry, p, props());
        String e = pendingEnrollmentOf(p);

        advance(Duration.ofMinutes(1));
        ingest(bye, p, props());

        assertThat(status(e)).isEqualTo("EXITED");
        JsonNode items = deliveries(e);
        assertThat(items).hasSize(1);
        assertCancelled(items.get(0), "ENROLLMENT_ENDED", clock.instant());
    }

    @Test
    void naturalCompletion_doesNotWriteCancelledRecord() throws Exception {
        String entry = "evt_en_in_" + uniq();
        publish(sendThenWait(Map.of(), entry));
        String e = enrollToPendingSend(entry);
        relay.runOnce();
        advance(Duration.ofDays(2));
        assertThat(status(e)).isNotEqualTo("WAITING");
        assertThat(jdbc.queryForObject(
                        "select count(*) from delivery_task where enrollment_id = ? and status = 'CANCELLED'",
                        Integer.class,
                        e))
                .as("自然走完不是取消")
                .isZero();
    }
}
