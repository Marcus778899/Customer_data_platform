package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 回歸 spec 02 §5 步驟 3：截斷、白名單後快照仍超過 64 KB -> 不建立 Enrollment，只回滾該綁定（同事件其他旅程照常），
 * 事件依派發重試退避，達 ENGINE_DISPATCH_MAX_ATTEMPTS（測試設定 5）標 EXHAUSTED，失敗原因記 SnapshotTooLargeException。
 * 做法：旅程 A 的 global_exit 引用 trigger.properties.blob（白名單保留），事件帶 70 KB 的 blob 字串（不是陣列，
 * 步驟 1 截不掉）；旅程 B 不引用，白名單丟掉 blob 後可正常建立。
 */
class SnapshotTooLargeAcceptanceTest extends StopCancelBase {

    @Test
    void stillTooLargeAfterWhitelist_retriesThenExhausted_noEnrollment_otherBindingUnaffected() throws Exception {
        String ev = "evt_big_" + uniq();
        String big =
                publish(def(
                        Map.of(
                                "global_exit",
                                Map.of(
                                        "conditions",
                                        List.of(Map.of(
                                                "type",
                                                "attribute",
                                                "field",
                                                "trigger.properties.blob",
                                                "operator",
                                                "EQ",
                                                "value",
                                                "x")),
                                        "on_match",
                                        "EXITED")),
                        List.of(start(), src(ev, "wait_1"), waitDelay("wait_1", "P1D", "end_1"), end("end_1"))));
        String small = publish(def(Map.of(), List.of(start(), src(ev, "end_1"), end("end_1"))));
        String p = ulid();

        String eventId = ingestOnly(ev, p, props("cart_id", "A", "blob", "y".repeat(70_000)));
        dispatcher.runOnce();
        assertThat(attempts(eventId)).as("第 1 次失敗").isEqualTo(1);

        for (int attempt = 2; attempt <= 5; attempt++) {
            assertThat(outcome(eventId)).as("第 %d 次前仍可重試", attempt).isNull();
            clock.advance(Duration.ofSeconds(31));
            dispatcher.runOnce();
            assertThat(attempts(eventId)).isEqualTo(attempt);
        }

        assertThat(outcome(eventId)).isEqualTo("EXHAUSTED");
        assertThat(jdbc.queryForObject(
                        "select last_dispatch_error from event where event_id = ?", String.class, eventId))
                .contains("SnapshotTooLargeException");
        assertThat(count(big, p)).as("快照過大的綁定不建立").isZero();
        assertThat(count(small, p)).as("同事件其他旅程照常，重試不重複建立").isEqualTo(1);

        // 放棄後不再重試
        clock.advance(Duration.ofMinutes(10));
        dispatcher.runOnce();
        assertThat(attempts(eventId)).isEqualTo(5);

        // 過大的事件不影響後續事件
        String p2 = ulid();
        String ok = ingest(ev, p2, props("cart_id", "B"));
        assertThat(outcome(ok)).isEqualTo("DISPATCHED");
        assertThat(count(big, p2)).isEqualTo(1);
        assertThat(count(small, p2)).isEqualTo(1);
    }

    private int count(String journeyId, String profileId) {
        return jdbc.queryForObject(
                "select count(*) from enrollment_record where journey_id = ? and profile_id = ?",
                Integer.class,
                journeyId,
                profileId);
    }

    private String outcome(String eventId) {
        return jdbc.queryForObject("select dispatch_outcome from event where event_id = ?", String.class, eventId);
    }

    private int attempts(String eventId) {
        return jdbc.queryForObject("select dispatch_attempts from event where event_id = ?", Integer.class, eventId);
    }
}
