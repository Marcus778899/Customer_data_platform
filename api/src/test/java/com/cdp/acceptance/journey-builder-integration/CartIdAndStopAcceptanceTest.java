package com.cdp.acceptance.journeybuilderintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** 驗收條件 4（同一 cart_id 送 5 次只建 1 筆）與 5（停止立即生效、未送出的訊息不發出）。 */
class CartIdAndStopAcceptanceTest extends BuilderBase {

    private static final String CART_PATH = "$.trigger.properties.cart_id";

    @Test
    void sameCartIdSentFiveTimes_createsOneEnrollment() throws Exception {
        String ev = "cart_item_added_" + uniq();
        String journey = publish(def(
                Map.of("reentry", Map.of("mode", "DEDUP_KEY", "dedup_key_path", CART_PATH)),
                List.of(start(), src(ev, null, "wait_1"), waitDelay("wait_1", "P7D", "end_1"), end("end_1"))));
        String p = ulid();
        Instant at = clock.instant();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int n = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    sendOnly(ev, p, props("cart_id", "CART-99201", "n", n), at);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < 3; i++) {
            dispatcher.runOnce();
            advanceLoop.runOnce();
        }
        assertThat(enrollmentCount(p)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select dedup_key from enrollment_record where profile_id = ?", String.class, p))
                .isEqualTo("CART-99201");
        assertThat(statsNode(journey, 1, "src_1").path("entered").asInt()).isEqualTo(1);

        // 另一個 cart_id 仍可進入
        ingestAt(ev, p, props("cart_id", "CART-99202"), at);
        assertThat(enrollmentCount(p)).isEqualTo(2);
    }

    private void sendOnly(String ev, String p, Map<String, Object> props, Instant at) throws Exception {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("event_id", ulid());
        e.put("source_type", "CUSTOMER_BEHAVIOR");
        e.put("source_id", "web-sdk@2.1.0");
        e.put("name", ev);
        e.put("occurred_at", at.toString());
        e.put("identity", Map.of("profile_id", p));
        e.put("properties", props);
        Resp r = com.cdp.acceptance.security.AuthHttp.bearer(
                port,
                "POST",
                "/api/v1/events",
                com.cdp.acceptance.security.AuthHttp.INGEST_KEY,
                JSON.writeValueAsString(e));
        assertThat(r.status()).as(r.body()).isEqualTo(202);
    }

    @Test
    void stopJourney_cancelsInFlightMessages_mockReceivesNone() throws Exception {
        String ev = "evt_stop_" + uniq();
        String journey = publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(ev, null, "wait_1"),
                        waitDelay("wait_1", "PT1H", "send_1"),
                        send("send_1", Map.of("sent", "wait_2")),
                        waitDelay("wait_2", "P1D", "end_1"),
                        end("end_1"))));
        String inFlight = ulid();
        ingest(ev, inFlight, props());
        String eFlight = enrollmentOf(inFlight);
        advance(Duration.ofHours(1).plusSeconds(1)); // 已寫 outbox，尚未 relay
        assertThat(outboxStatuses(eFlight)).containsExactly("PENDING");
        String waiting = ulid();
        ingest(ev, waiting, props());
        String eWait = enrollmentOf(waiting);

        Actor admin = actor("ADMIN");
        Resp stop = as(admin, "POST", "/api/v1/journeys/" + journey + "/stop", Map.of("reason", "活動取消"));
        assertThat(stop.status()).as(stop.body()).isEqualTo(200);
        assertThat(stop.json().path("status").asString()).isEqualTo("ABORTED");

        assertThat(outboxStatuses(eFlight)).as("立即生效").containsExactly("CANCELLED");
        relay.runOnce();
        Thread.sleep(1500);
        assertThat(mockCount(eFlight)).isZero();
        assertThat(mockCount(eWait)).isZero();

        // 時間再走、新事件都不會有訊息或新進度
        advance(Duration.ofDays(2));
        relay.runOnce();
        ingest(ev, ulid(), props());
        Thread.sleep(1000);
        assertThat(mockCount(eFlight) + mockCount(eWait)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from enrollment_record where journey_id = ?", Integer.class, journey))
                .isEqualTo(2);
        JsonNode d = apiGet("/api/v1/journeys/" + journey);
        assertThat(d.path("status").asString()).isEqualTo("ABORTED");
    }
}
