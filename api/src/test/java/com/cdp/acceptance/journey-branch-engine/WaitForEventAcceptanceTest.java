package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** WaitForEventNode：occurred／timeout 兩個出口、只認同客戶同名事件、timeout 為 null 不逾時。 */
class WaitForEventAcceptanceTest extends BranchBase {

    private record Ev(String entry, String waitName) {}

    private Ev journey(String timeoutIso) throws Exception {
        Ev ev = new Ev("evt_w_in_" + uniq(), "evt_w_" + uniq());
        Map<String, Object> outs = timeoutIso == null
                ? Map.of("occurred", "end_occ")
                : Map.of("occurred", "end_occ", "timeout", "end_to");
        List<Map<String, Object>> nodes = new java.util.ArrayList<>(List.of(
                start(),
                src(ev.entry(), null, "wait_1"),
                waitEvent("wait_1", ev.waitName(), timeoutIso, null, outs),
                end("end_occ")));
        if (timeoutIso != null) nodes.add(end("end_to"));
        publish(def(Map.of(), nodes));
        return ev;
    }

    @Test
    void eventArrives_takesOccurredPort() throws Exception {
        Ev ev = journey("PT1H");
        String p = ulid();
        ingest(ev.entry(), p, props());
        String e = enrollmentOf(p);
        assertThat(status(e)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject(
                        "select exited_at is null from node_trace where enrollment_id = ? and node_id = 'wait_1'",
                        Boolean.class,
                        e))
                .as("等待中的節點尚未離開")
                .isTrue();

        advance(Duration.ofMinutes(5));
        ingest(ev.waitName(), p, props());
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(lastPort(e, "wait_1")).isEqualTo("occurred");
        assertThat(visits(e, "end_occ")).isEqualTo(1);
        assertThat(visits(e, "end_to")).isZero();

        // 之後逾時時間到了也不會再動
        advance(Duration.ofHours(2));
        assertThat(visits(e, "end_to")).isZero();
        assertThat(status(e)).isEqualTo("COMPLETED");
    }

    @Test
    void noEvent_timeoutPortAfterDeadline_notBefore() throws Exception {
        Ev ev = journey("PT1H");
        String p = ulid();
        ingest(ev.entry(), p, props());
        String e = enrollmentOf(p);

        advance(Duration.ofMinutes(59));
        assertThat(status(e)).as("未到期").isEqualTo("WAITING");

        advance(Duration.ofMinutes(2));
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(lastPort(e, "wait_1")).isEqualTo("timeout");
        assertThat(visits(e, "end_to")).isEqualTo(1);
        assertThat(visits(e, "end_occ")).isZero();
    }

    @Test
    void otherProfileOrOtherEvent_doesNotWake() throws Exception {
        Ev ev = journey("PT1H");
        String p = ulid();
        String other = ulid();
        ingest(ev.entry(), p, props());
        String e = enrollmentOf(p);

        advance(Duration.ofSeconds(1));
        ingest(ev.waitName(), other, props());
        ingest("evt_unrelated_" + uniq(), p, props());
        assertThat(status(e)).isEqualTo("WAITING");
        assertThat(enrollmentCount(other)).as("等待事件不是入口，不建旅程進度").isZero();
    }

    @Test
    void nullTimeout_waitsIndefinitely_untilEvent() throws Exception {
        Ev ev = journey(null);
        String p = ulid();
        ingest(ev.entry(), p, props());
        String e = enrollmentOf(p);

        advance(Duration.ofDays(60));
        assertThat(status(e)).isEqualTo("WAITING");

        ingest(ev.waitName(), p, props());
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(lastPort(e, "wait_1")).isEqualTo("occurred");
    }

    @Test
    void waitDecisionReason_isReadableAndWithinLimit() throws Exception {
        Ev ev = journey("PT1H");
        String p = ulid();
        ingest(ev.entry(), p, props());
        String e = enrollmentOf(p);
        advance(Duration.ofHours(2));
        assertThat(status(e)).isEqualTo("COMPLETED");
        String reason = jdbc.queryForObject(
                "select decision_reason from node_trace where enrollment_id = ? and node_id = 'wait_1'",
                String.class,
                e);
        assertThat(reason).isNotBlank();
        assertThat(reason.length()).isLessThanOrEqualTo(200);
    }
}
