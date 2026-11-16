package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 縮減版場景一（D-046、D-085）端到端：加入購物車（金額 > 500）→ 等結帳 1 小時；結帳 = 目標，逾時 = 發提醒。
 * 全程以 API 建旅程、攝取事件、查詢旅程進度與節點統計。
 */
class ScenarioOneAcceptanceTest extends BranchBase {

    @Test
    void cartAbandonScenario_runsEndToEnd() throws Exception {
        String cart = "cart_item_added_" + uniq();
        String order = "order_completed_" + uniq();
        String journey = publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(cart, leaf("properties.amount", "GT", 500), "wait_1"),
                        waitEvent("wait_1", order, "PT1H", null, Map.of("occurred", "goal_1", "timeout", "send_1")),
                        node("goal_1", "GoalNode", Map.of("goal_id", "checkout"), Map.of()),
                        send("send_1", Map.of("sent", "end_1")),
                        end("end_1"))));

        String bought = ulid();
        String abandoned = ulid();
        String small = ulid();
        ingest(cart, bought, props("amount", 800));
        ingest(cart, abandoned, props("amount", 800));
        ingest(cart, small, props("amount", 300));

        // 金額不足不建旅程進度
        assertThat(apiGet("/api/v1/enrollments?q=" + small).path("items")).isEmpty();

        // 買了：目標達成，不發訊息
        advance(Duration.ofMinutes(20));
        ingest(order, bought, props());
        String eb = enrollmentOf(bought);
        JsonNode detailB = apiGet("/api/v1/enrollments/" + eb);
        assertThat(detailB.path("status").asString()).isEqualTo("GOAL_MET");
        List<String> nodesB = new ArrayList<>();
        for (JsonNode t : detailB.path("trace")) nodesB.add(t.path("node_id").asString());
        assertThat(nodesB).contains("src_1", "wait_1", "goal_1").doesNotContain("send_1");
        assertThat(apiGet("/api/v1/enrollments/" + eb + "/deliveries").path("items")).isEmpty();

        // 沒買：逾時走 timeout，發出一則訊息後結束
        advance(Duration.ofMinutes(45));
        String ea = enrollmentOf(abandoned);
        assertThat(status(ea)).isEqualTo("COMPLETED");
        relay.runOnce();
        long deadline = System.currentTimeMillis() + 15_000;
        int sent = 0;
        while (System.currentTimeMillis() < deadline) {
            sent = jdbc.queryForObject(
                    "select count(*) from mock_channel_message where enrollment_id = ?", Integer.class, ea);
            if (sent > 0) break;
            Thread.sleep(200);
        }
        assertThat(sent).as("Mock 通道收到提醒").isEqualTo(1);
        JsonNode detailA = apiGet("/api/v1/enrollments/" + ea);
        assertThat(detailA.path("status").asString()).isEqualTo("COMPLETED");
        List<String> nodesA = new ArrayList<>();
        for (JsonNode t : detailA.path("trace")) {
            nodesA.add(t.path("node_id").asString());
            if (t.path("node_id").asString().equals("wait_1")) {
                assertThat(t.path("out_port").asString()).isEqualTo("timeout");
            }
        }
        assertThat(nodesA).contains("src_1", "wait_1", "send_1", "end_1").doesNotContain("goal_1");

        // 節點統計：兩人進入，一人 occurred、一人 timeout
        JsonNode stats = apiGet("/api/v1/journeys/" + journey + "/versions/1/node-stats");
        JsonNode wait = null;
        JsonNode src = null;
        for (JsonNode it : stats.path("items")) {
            if (it.path("node_id").asString().equals("wait_1")) wait = it;
            if (it.path("node_id").asString().equals("src_1")) src = it;
        }
        assertThat(src).isNotNull();
        assertThat(src.path("entered").asInt()).isEqualTo(2);
        assertThat(wait).isNotNull();
        assertThat(wait.path("out_ports").path("occurred").asInt()).isEqualTo(1);
        assertThat(wait.path("out_ports").path("timeout").asInt()).isEqualTo(1);
        assertThat(wait.path("current").asInt()).isZero();
    }
}
