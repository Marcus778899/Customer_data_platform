package com.cdp.acceptance.enrollmentreentryengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.TriggerSnapshot;

/**
 * 驗收條件 1：派發冪等（同一事件重送）與 G3（不同事件、同去重鍵）分開驗；含併發。
 * 併發直接呼叫 JourneyRuntime.start（重入判斷的落點，spec 02 §6「落地」），5 個執行緒同時進入。
 */
class ReentryIdempotencyAcceptanceTest extends ReentryBase {

    private TriggerSnapshot trigger(String eventName, String eventId, Map<String, Object> properties) {
        return new TriggerSnapshot(eventId, eventName, clock.instant(), "CUSTOMER_BEHAVIOR", properties, Map.of());
    }

    private EnrollmentState start(String journeyId, String event, String profile, String eventId, Map<String, Object> p) {
        return runtime.start(journeyId, versionOf(journeyId), "src_1", profile, trigger(event, eventId, p), eventId);
    }

    private List<EnrollmentState> concurrently(int n, java.util.function.IntFunction<Callable<EnrollmentState>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<EnrollmentState>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<EnrollmentState> c = task.apply(i);
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return c.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<EnrollmentState> out = new ArrayList<>();
            for (Future<EnrollmentState> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 同一事件重送（與 mode 無關） ----------

    @Test
    void sameEventRedelivered_createsOne_underUnlimited_andRecordsTriggerEventId() throws Exception {
        String ev = "evt_idem_" + uniq();
        String j = quickJourney(ev, Map.of());
        String p = ulid();
        String eventId = EngineTestSupport.ulid();
        EnrollmentState a = start(j, ev, p, eventId, props("cart_id", "A"));
        EnrollmentState b = start(j, ev, p, eventId, props("cart_id", "A"));
        assertThat(count(j, p)).isEqualTo(1);
        assertThat(b.enrollmentId()).isEqualTo(a.enrollmentId());
        assertThat(jdbc.queryForObject(
                        "select trigger_event_id from enrollment_record where enrollment_id = ?",
                        String.class,
                        a.enrollmentId()))
                .isEqualTo(eventId);
        // 不同事件仍各建一筆（UNLIMITED）
        start(j, ev, p, EngineTestSupport.ulid(), props("cart_id", "A"));
        assertThat(count(j, p)).isEqualTo(2);
    }

    @Test
    void sameEventRedelivered_createsOne_underCooldown_andReturnsExisting() throws Exception {
        String ev = "evt_idem2_" + uniq();
        String j = longJourney(ev, reentry("COOLDOWN", null, "PT1H"));
        String p = ulid();
        String eventId = EngineTestSupport.ulid();
        EnrollmentState a = start(j, ev, p, eventId, props());
        EnrollmentState b = start(j, ev, p, eventId, props());
        assertThat(count(j, p)).isEqualTo(1);
        assertThat(b.enrollmentId()).isEqualTo(a.enrollmentId());
    }

    @Test
    void sameEventRedelivered_concurrently_createsOne() throws Exception {
        String ev = "evt_idem3_" + uniq();
        String j = quickJourney(ev, Map.of());
        String p = ulid();
        String eventId = EngineTestSupport.ulid();
        List<EnrollmentState> r = concurrently(5, i -> () -> start(j, ev, p, eventId, props("n", 1)));
        assertThat(count(j, p)).isEqualTo(1);
        assertThat(r).extracting(EnrollmentState::enrollmentId).containsOnly(r.get(0).enrollmentId());
    }

    // ---------- G3：不同事件、同去重鍵 ----------

    @Test
    void dedupKey_fiveDifferentEventsSameCartId_concurrently_createsOne_noErrors() throws Exception {
        String ev = "evt_g3_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        List<EnrollmentState> r = concurrently(
                5, i -> () -> start(j, ev, p, EngineTestSupport.ulid(), props("cart_id", "CART-99201", "n", i)));
        assertThat(count(j, p)).as("併發 5 個不同事件、同一 cart_id").isEqualTo(1);
        assertThat(r).as("撞約束視為重複，回傳既有、不丟例外").extracting(EnrollmentState::enrollmentId).containsOnly(r.get(0).enrollmentId());
        assertThat(dedupKeys(j, p)).containsExactly("CART-99201");
    }

    @Test
    void dedupKey_concurrentDifferentCartIds_enrollEachOnce() throws Exception {
        String ev = "evt_g3b_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        concurrently(6, i -> () -> start(j, ev, p, EngineTestSupport.ulid(), props("cart_id", "K" + (i % 3))));
        assertThat(count(j, p)).isEqualTo(3);
        assertThat(dedupKeys(j, p)).containsExactlyInAnyOrder("K0", "K1", "K2");
    }

    @Test
    void once_concurrentDifferentEvents_createsOne() throws Exception {
        String ev = "evt_g3c_" + uniq();
        String j = quickJourney(ev, reentry("ONCE", null, null));
        String p = ulid();
        concurrently(5, i -> () -> start(j, ev, p, EngineTestSupport.ulid(), props("n", i)));
        assertThat(count(j, p)).isEqualTo(1);
        assertThat(dedupKeys(j, p)).containsExactly("__ONCE__");
    }

    @Test
    void cooldown_concurrentDifferentEvents_sameProfile_createsOne() throws Exception {
        String ev = "evt_g3d_" + uniq();
        String j = longJourney(ev, reentry("COOLDOWN", null, "PT1H"));
        String p = ulid();
        concurrently(5, i -> () -> start(j, ev, p, EngineTestSupport.ulid(), props("n", i)));
        assertThat(count(j, p)).as("同一客戶併發觸發不會各自通過冷卻檢查").isEqualTo(1);
    }

    @Test
    void dedupKeyWithCooldown_concurrentDifferentKeys_sameProfile_createsOne() throws Exception {
        String ev = "evt_g3e_" + uniq();
        String j = longJourney(ev, reentry("DEDUP_KEY_WITH_COOLDOWN", CART_PATH, "PT1H"));
        String p = ulid();
        concurrently(5, i -> () -> start(j, ev, p, EngineTestSupport.ulid(), props("cart_id", "K" + i)));
        assertThat(count(j, p)).isEqualTo(1);
    }

    @Test
    void dedupKey_viaHttp_fiveConcurrentIngests_sameCartId_createsOne() throws Exception {
        String ev = "evt_g3f_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        Instant now = clock.instant();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int n = i;
                fs.add(pool.submit(() -> {
                    ingestNoDispatch(ev, p, props("cart_id", "CART-77", "n", n), now);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        dispatcher.runOnce();
        advanceLoop.runOnce();
        assertThat(count(j, p)).isEqualTo(1);
    }
}
