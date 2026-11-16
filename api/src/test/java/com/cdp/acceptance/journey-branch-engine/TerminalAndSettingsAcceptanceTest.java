package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 驗收條件 1、2：ExitNode／GoalNode、settings.goal、settings.global_exit；終態同交易取消未送出的訊息。 */
class TerminalAndSettingsAcceptanceTest extends BranchBase {

    private static Map<String, Object> goalSettings(String event) {
        return Map.of(
                "goal",
                Map.of("goal_id", "purchase", "condition", Map.of("type", "event", "event", event), "window", "P7D"));
    }

    /** start, src_1, wait_1(PT1H), send_1, wait_2(P1D), end_1。 */
    private String sendThenWait(String entry, Map<String, Object> settings) throws Exception {
        return publish(def(
                settings,
                List.of(
                        start(),
                        src(entry, null, "wait_1"),
                        waitDelay("wait_1", "PT1H", "send_1"),
                        send("send_1", Map.of("sent", "wait_2")),
                        waitDelay("wait_2", "P1D", "end_1"),
                        end("end_1"))));
    }

    /** 進到已排入 outbox、等在 wait_2 的狀態。 */
    private String enrollToPendingSend(String entry, String profile) throws Exception {
        ingest(entry, profile, props());
        String e = enrollmentOf(profile);
        advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(status(e)).isEqualTo("WAITING");
        assertThat(outboxStatuses(e)).as("訊息已排入、尚未送出").containsExactly("PENDING");
        return e;
    }

    @Test
    void exitNode_endsAsExited_withReason() throws Exception {
        String event = "evt_ex_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(event, null, "exit_1"),
                        node("exit_1", "ExitNode", Map.of("reason", "對照組不發送"), Map.of()))));
        String p = ulid();
        ingest(event, p, props());
        String e = enrollmentOf(p);
        assertThat(status(e)).isEqualTo("EXITED");
        assertThat(terminated(e)).isTrue();
        assertThat(terminationReason(e)).contains("對照組不發送");
        assertThat(visits(e, "exit_1")).isEqualTo(1);
    }

    @Test
    void goalNode_endsAsGoalMet() throws Exception {
        String event = "evt_gn_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(event, null, "goal_1"),
                        node("goal_1", "GoalNode", Map.of("goal_id", "purchase"), Map.of()))));
        String p = ulid();
        ingest(event, p, props());
        String e = enrollmentOf(p);
        assertThat(status(e)).isEqualTo("GOAL_MET");
        assertThat(terminated(e)).isTrue();
        assertThat(visits(e, "goal_1")).isEqualTo(1);
    }

    @Test
    void settingsGoal_hitWhileWaiting_goalMet_andCancelsPendingMessage() throws Exception {
        String entry = "evt_sg_in_" + uniq();
        String order = "evt_sg_order_" + uniq();
        sendThenWait(entry, goalSettings(order));

        String winner = ulid();
        String bystander = ulid();
        String ew = enrollToPendingSend(entry, winner);
        String eb = enrollToPendingSend(entry, bystander);

        advance(Duration.ofMinutes(1));
        ingest(order, winner, props());

        assertThat(status(ew)).isEqualTo("GOAL_MET");
        assertThat(terminated(ew)).isTrue();
        assertThat(terminationReason(ew)).isNotBlank();
        assertThat(outboxStatuses(ew)).as("同交易取消未送出的訊息").containsExactly("CANCELLED");
        assertThat(status(eb)).as("別人的事件不影響").isEqualTo("WAITING");
        assertThat(outboxStatuses(eb)).containsExactly("PENDING");

        // 取消的訊息不會被送出
        relay.runOnce();
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject(
                        "select count(*) from mock_channel_message where enrollment_id = ?", Integer.class, ew))
                .isZero();

        // 終態後再來事件、時間再走，狀態不變
        ingest(order, winner, props());
        advance(Duration.ofDays(2));
        assertThat(status(ew)).isEqualTo("GOAL_MET");
    }

    @Test
    void settingsGoal_eventBeforeEnrollment_doesNotCount() throws Exception {
        String entry = "evt_sgb_in_" + uniq();
        String order = "evt_sgb_order_" + uniq();
        sendThenWait(entry, goalSettings(order));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);
        advance(Duration.ofMinutes(10));
        ingestAt(order, p, props(), clock.instant().minus(Duration.ofMinutes(20)));
        assertThat(status(e)).as("occurred_at 早於進入旅程").isEqualTo("WAITING");
    }

    @Test
    void globalExit_eventConditions_defaultAndPerConditionOnMatch() throws Exception {
        String entry = "evt_ge_in_" + uniq();
        String bought = "evt_ge_bought_" + uniq();
        String unsub = "evt_ge_unsub_" + uniq();
        sendThenWait(
                entry,
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(
                                        Map.of("type", "event", "event", bought, "since", "ENROLLMENT", "on_match", "GOAL_MET"),
                                        Map.of("type", "event", "event", unsub, "since", "ENROLLMENT")),
                                "on_match",
                                "EXITED")));
        String pBought = ulid();
        String pUnsub = ulid();
        String eBought = enrollToPendingSend(entry, pBought);
        String eUnsub = enrollToPendingSend(entry, pUnsub);

        advance(Duration.ofMinutes(1));
        ingest(bought, pBought, props());
        ingest(unsub, pUnsub, props());

        assertThat(status(eBought)).as("條件覆寫為 GOAL_MET").isEqualTo("GOAL_MET");
        assertThat(status(eUnsub)).as("沿用頂層 EXITED").isEqualTo("EXITED");
        assertThat(outboxStatuses(eBought)).containsExactly("CANCELLED");
        assertThat(outboxStatuses(eUnsub)).containsExactly("CANCELLED");
        assertThat(terminationReason(eBought)).isNotBlank();
        assertThat(terminationReason(eUnsub)).isNotBlank();
    }

    @Test
    void globalExit_whileWaitingForEvent_cancelsTimer() throws Exception {
        String entry = "evt_gw_in_" + uniq();
        String wait = "evt_gw_wait_" + uniq();
        String bye = "evt_gw_bye_" + uniq();
        publish(def(
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(Map.of("type", "event", "event", bye, "since", "ENROLLMENT")),
                                "on_match",
                                "EXITED")),
                List.of(
                        start(),
                        src(entry, null, "wait_1"),
                        waitEvent("wait_1", wait, "PT1H", null, Map.of("occurred", "end_occ", "timeout", "end_to")),
                        end("end_occ"),
                        end("end_to"))));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);
        advance(Duration.ofMinutes(10));
        ingest(bye, p, props());
        assertThat(status(e)).isEqualTo("EXITED");

        advance(Duration.ofHours(3));
        assertThat(status(e)).isEqualTo("EXITED");
        assertThat(visits(e, "end_to")).as("計時器已取消").isZero();
        assertThat(visits(e, "end_occ")).isZero();
    }

    @Test
    void globalExit_attributeCondition_evaluatedOnAdvance() throws Exception {
        String entry = "evt_ga_in_" + uniq();
        sendThenWait(
                entry,
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(Map.of(
                                        "type",
                                        "attribute",
                                        "field",
                                        "trigger.properties.opt_out",
                                        "operator",
                                        "EQ",
                                        "value",
                                        true)),
                                "on_match",
                                "EXITED")));
        String out = ulid();
        ingest(entry, out, props("opt_out", true));
        String eo = enrollmentOf(out);
        assertThat(status(eo)).isEqualTo("EXITED");
        assertThat(outboxStatuses(eo)).isEmpty();
        assertThat(visits(eo, "send_1")).isZero();

        String stay = ulid();
        ingest(entry, stay, props("opt_out", false));
        assertThat(status(enrollmentOf(stay))).isEqualTo("WAITING");
    }
}
