package com.cdp.acceptance.journeybuilderintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 驗收條件 1：縮減版場景一（D-046，spec 03 §7 拿掉 WebhookNode 與 UpdateProfileNode，金額 > 500 篩選保留 D-085）
 * 經草稿、EDITOR 送審、另一位 ADMIN 核准後上線，再以事件實際跑通所有出口。
 */
class ScenarioOneEndToEndAcceptanceTest extends BuilderBase {

    private static Map<String, Object> split(String id) {
        Map<String, Object> coupon = new LinkedHashMap<>();
        coupon.put("port", "coupon");
        coupon.put("weight", 50);
        coupon.put("label", "發折價券");
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("port", "control");
        control.put("weight", 50);
        control.put("label", "對照組");
        control.put("is_control", true);
        return node(
                id,
                "SplitTestNode",
                Map.of("variants", List.of(coupon, control), "assignment_seed", "STABLE"),
                Map.of("coupon", "send_coupon", "control", "exit_control"));
    }

    private static Map<String, Object> sendOn(String id, String tpl, Map<String, Object> outs) {
        return node(
                id, "SendMessageNode", Map.of("template_id", tpl, "channel_id", "ch_a", "on_skip", "CONTINUE"), outs);
    }

    /** spec 03：SHA-256(journey_id:node_id:profile_id) 前 8 位元組（無號）mod 100，< 50 為 coupon。 */
    private static String portOf(String journeyId, String profileId) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((journeyId + ":split_1:" + profileId).getBytes(StandardCharsets.UTF_8));
        long bucket = new BigInteger(1, java.util.Arrays.copyOf(h, 8)).mod(BigInteger.valueOf(100)).longValue();
        return bucket < 50 ? "coupon" : "control";
    }

    private static String profileIn(String journeyId, String port) throws Exception {
        for (int i = 0; i < 500; i++) {
            String p = ulid();
            if (portOf(journeyId, p).equals(port)) return p;
        }
        throw new IllegalStateException("no profile for " + port);
    }

    @Test
    void reducedScenarioOne_publishedByReview_runsAllExits() throws Exception {
        String cart = "cart_item_added_" + uniq();
        String order = "order_completed_" + uniq();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("reentry", Map.of("mode", "DEDUP_KEY", "dedup_key_path", "$.trigger.properties.cart_id"));
        settings.put(
                "goal",
                Map.of("goal_id", "checkout", "condition", Map.of("type", "event", "event", order), "window", "P7D"));
        Map<String, Object> d = def(
                settings,
                List.of(
                        start(),
                        src(cart, leaf("properties.amount", "GT", 500), "wait_1"),
                        waitDelay("wait_1", "PT1H", "cond_vip"),
                        cond(
                                "cond_vip",
                                leaf("member_tier", "IN", List.of("GOLD", "PLATINUM")),
                                "send_vip",
                                "send_normal"),
                        sendOn("send_vip", "tpl_vip_cart", Map.of("sent", "merge_1", "skipped", "merge_1")),
                        sendOn("send_normal", "tpl_std_cart", Map.of("sent", "merge_1", "skipped", "merge_1")),
                        node("merge_1", "MergeNode", Map.of(), Map.of("next", "wait_order_1")),
                        waitEvent(
                                "wait_order_1",
                                order,
                                "P1D",
                                null,
                                Map.of("occurred", "goal_1", "timeout", "split_1")),
                        node("goal_1", "GoalNode", Map.of("goal_id", "checkout"), Map.of()),
                        split("split_1"),
                        node("exit_control", "ExitNode", Map.of("reason", "對照組"), Map.of()),
                        sendOn("send_coupon", "tpl_coupon", Map.of("sent", "wait_order_2", "skipped", "wait_order_2")),
                        waitEvent(
                                "wait_order_2", order, "P2D", null, Map.of("occurred", "goal_1", "timeout", "end_1")),
                        end("end_1")));
        String journey = publish(d);

        String buyer = ulid(); // 1 小時內結帳
        String vipCoupon = profileIn(journey, "coupon"); // VIP、沒結帳、領券、再逾時
        String normalControl = profileIn(journey, "control"); // 一般、沒結帳、對照組
        String small = ulid(); // 金額不足
        putProfile(buyer, props("member_tier", "GOLD"));
        putProfile(vipCoupon, props("member_tier", "GOLD"));
        putProfile(normalControl, props("member_tier", "BASIC"));

        ingest(cart, buyer, props("amount", 900, "cart_id", "C-" + uniq()));
        ingest(cart, vipCoupon, props("amount", 800, "cart_id", "C-" + uniq()));
        ingest(cart, normalControl, props("amount", 700, "cart_id", "C-" + uniq()));
        ingest(cart, small, props("amount", 300, "cart_id", "C-" + uniq()));
        assertThat(enrollmentCount(small)).as("金額 <= 500 不進入").isZero();

        // 1 小時內結帳：GOAL_MET、沒有任何訊息
        advance(Duration.ofMinutes(20));
        ingest(order, buyer, props());
        String eBuyer = enrollmentOf(buyer);
        assertThat(status(eBuyer)).isEqualTo("GOAL_MET");
        assertThat(apiGet("/api/v1/enrollments/" + eBuyer + "/deliveries").path("items")).isEmpty();

        // 等滿 1 小時：VIP 走 send_vip、一般走 send_normal，各一則
        advance(Duration.ofMinutes(41));
        String eVip = enrollmentOf(vipCoupon);
        String eNormal = enrollmentOf(normalControl);
        assertThat(visits(eVip, "send_vip")).isEqualTo(1);
        assertThat(visits(eVip, "send_normal")).isZero();
        assertThat(visits(eNormal, "send_normal")).isEqualTo(1);
        assertThat(visits(eNormal, "send_vip")).isZero();
        assertThat(awaitMock(eVip, 1)).isEqualTo(1);
        assertThat(awaitMock(eNormal, 1)).isEqualTo(1);

        // 再等 24 小時未結帳：依分組進 coupon／control
        advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(lastPort(eVip, "wait_order_1")).isEqualTo("timeout");
        assertThat(lastPort(eVip, "split_1")).isEqualTo("coupon");
        assertThat(visits(eVip, "send_coupon")).isEqualTo(1);
        assertThat(status(eVip)).isEqualTo("WAITING");
        assertThat(awaitMock(eVip, 2)).as("提醒加折價券").isEqualTo(2);
        assertThat(lastPort(eNormal, "split_1")).isEqualTo("control");
        assertThat(status(eNormal)).isEqualTo("EXITED");
        assertThat(terminationReason(eNormal)).contains("對照組");
        assertThat(mockCount(eNormal)).as("對照組不再發送").isEqualTo(1);

        // 再等 48 小時仍未結帳：COMPLETED
        advance(Duration.ofDays(2).plusMinutes(1));
        assertThat(status(eVip)).isEqualTo("COMPLETED");
        assertThat(visits(eVip, "end_1")).isEqualTo(1);

        // 軌跡 API 與旅程頁人數
        JsonNode detail = apiGet("/api/v1/enrollments/" + eVip);
        List<String> nodes = new ArrayList<>();
        for (JsonNode t : detail.path("trace")) nodes.add(t.path("node_id").asString());
        assertThat(nodes)
                .containsSubsequence(
                        "src_1", "wait_1", "cond_vip", "send_vip", "merge_1", "wait_order_1", "split_1", "send_coupon",
                        "wait_order_2", "end_1");
        assertThat(statsNode(journey, 1, "src_1").path("entered").asInt()).isEqualTo(3);
        JsonNode splitStats = statsNode(journey, 1, "split_1");
        assertThat(splitStats.path("out_ports").path("coupon").asInt()).isEqualTo(1);
        assertThat(splitStats.path("out_ports").path("control").asInt()).isEqualTo(1);
    }
}
