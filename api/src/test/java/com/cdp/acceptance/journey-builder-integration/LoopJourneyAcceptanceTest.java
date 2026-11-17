package com.cdp.acceptance.journeybuilderintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** 驗收條件 6（API 面）：迴圈旅程（等事件逾時、發訊息、回到合流點）圈數不限，軌跡與節點統計反映每一圈。 */
class LoopJourneyAcceptanceTest extends BuilderBase {

    @Test
    void loopJourney_repeatsAndStatsCountEveryLap_thenGoalEndsIt() throws Exception {
        String entry = "evt_loop_in_" + uniq();
        String order = "evt_loop_order_" + uniq();
        String journey = publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(entry, null, "merge_1"),
                        node("merge_1", "MergeNode", Map.of(), Map.of("next", "wait_1")),
                        waitEvent("wait_1", order, "PT1H", null, Map.of("occurred", "goal_1", "timeout", "send_1")),
                        send("send_1", Map.of("sent", "merge_1", "skipped", "merge_1")),
                        node("goal_1", "GoalNode", Map.of("goal_id", "ordered"), Map.of()))));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);
        for (int lap = 0; lap < 3; lap++) {
            advance(Duration.ofHours(1).plusSeconds(1));
            assertThat(status(e)).isEqualTo("WAITING");
        }
        assertThat(visits(e, "wait_1")).isEqualTo(4);
        assertThat(visits(e, "send_1")).isEqualTo(3);
        assertThat(awaitMock(e, 3)).isEqualTo(3);

        JsonNode wait = statsNode(journey, 1, "wait_1");
        assertThat(wait.path("entered").asInt()).as("以人計").isEqualTo(1);
        assertThat(wait.path("visits").asInt()).isEqualTo(4);
        assertThat(wait.path("out_ports").path("timeout").asInt()).isEqualTo(3);
        assertThat(wait.path("current").asInt()).isEqualTo(1);

        JsonNode detail = apiGet("/api/v1/enrollments/" + e);
        List<String> trace = new ArrayList<>();
        for (JsonNode t : detail.path("trace")) trace.add(t.path("node_id").asString());
        assertThat(trace.stream().filter("merge_1"::equals).count()).isEqualTo(4);

        ingest(order, p, props());
        assertThat(status(e)).isEqualTo("GOAL_MET");
        assertThat(visits(e, "goal_1")).isEqualTo(1);
    }
}
