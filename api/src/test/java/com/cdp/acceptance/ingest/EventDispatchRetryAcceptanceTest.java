package com.cdp.acceptance.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.cdp.engine.JourneyRuntime;

/**
 * FIX-03a 驗收：事件派發退避重試與放棄（spec 07 §11、D-024、D-026）。
 * 失敗接縫：{@code @MockitoSpyBean JourneyRuntime} 對指定旅程的 start 拋例外。
 * 設定：最多 5 次、退避 10 秒起、上限 30 秒 → 失敗後等待 10、20、30、30 秒，第 5 次失敗即放棄。
 */
class EventDispatchRetryAcceptanceTest extends IngestAcceptanceBase {

    @MockitoSpyBean JourneyRuntime runtime;

    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    /** 對 failing 內的旅程 start 一律拋例外，其餘呼叫真實實作。 */
    private void armFailures() {
        doAnswer(
                        inv -> {
                            if (failing.contains(inv.<String>getArgument(0))) {
                                throw new IllegalStateException("binding boom");
                            }
                            return inv.callRealMethod();
                        })
                .when(runtime)
                .start(any(), anyInt(), any(), any(), any(), any());
    }

    private long startCalls(String journeyId) {
        return mockingDetails(runtime).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("start"))
                .filter(i -> journeyId.equals(i.getArgument(0)))
                .count();
    }

    /** 快轉到該事件的 next_dispatch_at（剛好到期）。 */
    private void advanceToDue(String eventId) {
        Instant due = instantOf(eventRow(eventId).get("next_dispatch_at"));
        clock.advance(Duration.between(clock.instant(), due));
    }

    @Test
    void persistentFailure_backsOffExponentiallyWithCap_andDoesNotBlockLaterEvents()
            throws Exception {
        String badEventName = newEventName();
        String badJourney = createJourney(badEventName);
        String okEventName = newEventName();
        String okJourney = createJourney(okEventName);
        failing.add(badJourney);
        armFailures();

        String badProfile = ulid();
        String badEvent = ingestOk(badEventName, badProfile);
        String okProfile1 = ulid();
        String okEvent1 = ingestOk(okEventName, okProfile1);

        // 第 1 次失敗：attempts=1，下次 = now+10s；同批後面的事件照常派發
        dispatcher.runOnce();
        Map<String, Object> row = eventRow(badEvent);
        assertThat(attempts(row)).isEqualTo(1);
        assertThat(row.get("dispatched_at")).isNull();
        assertThat(row.get("dispatch_outcome")).isNull();
        assertThat(String.valueOf(row.get("last_dispatch_error")))
                .startsWith("IllegalStateException: ");
        assertThat(instantOf(row.get("next_dispatch_at")))
                .isEqualTo(clock.instant().plus(Duration.ofSeconds(10)));
        assertThat(eventRow(okEvent1).get("dispatch_outcome")).isEqualTo("DISPATCHED");
        assertThat(enrollmentCount(okProfile1, okJourney)).isEqualTo(1);

        // 退避期間：失敗事件不被重試；新事件照常派發
        clock.advance(Duration.ofSeconds(5));
        String okProfile2 = ulid();
        String okEvent2 = ingestOk(okEventName, okProfile2);
        dispatcher.runOnce();
        assertThat(startCalls(badJourney)).isEqualTo(1);
        assertThat(attempts(eventRow(badEvent))).isEqualTo(1);
        assertThat(eventRow(okEvent2).get("dispatch_outcome")).isEqualTo("DISPATCHED");
        assertThat(enrollmentCount(okProfile2, okJourney)).isEqualTo(1);

        // 之後每次到期：20 → 30（上限）→ 30（40 被上限截斷）
        long[] expectedDelays = {20, 30, 30};
        for (int i = 0; i < expectedDelays.length; i++) {
            advanceToDue(badEvent);
            dispatcher.runOnce();
            Map<String, Object> r = eventRow(badEvent);
            assertThat(attempts(r)).isEqualTo(i + 2);
            assertThat(instantOf(r.get("next_dispatch_at")))
                    .as("第 %d 次失敗後的退避", i + 2)
                    .isEqualTo(clock.instant().plus(Duration.ofSeconds(expectedDelays[i])));
            assertThat(r.get("dispatched_at")).isNull();
        }
        assertThat(enrollmentCount(badProfile)).isZero();
    }

    @Test
    void exhaustion_afterExactlyMaxAttempts_marksExhausted_andCreatesNoEnrollment()
            throws Exception {
        String eventName = newEventName();
        String journey = createJourney(eventName);
        failing.add(journey);
        armFailures();
        String profile = ulid();
        String eventId = ingestOk(eventName, profile);

        for (int attempt = 1; attempt <= 5; attempt++) {
            if (attempt > 1) {
                advanceToDue(eventId);
            }
            dispatcher.runOnce();
            Map<String, Object> r = eventRow(eventId);
            assertThat(attempts(r)).isEqualTo(attempt);
            if (attempt < 5) {
                assertThat(r.get("dispatched_at")).as("第 %d 次後仍可重試", attempt).isNull();
                assertThat(r.get("dispatch_outcome")).isNull();
            }
        }

        Map<String, Object> r = eventRow(eventId);
        assertThat(r.get("dispatch_outcome")).isEqualTo("EXHAUSTED");
        assertThat(instantOf(r.get("dispatched_at"))).isEqualTo(clock.instant());
        assertThat(attempts(r)).isEqualTo(5);
        assertThat(String.valueOf(r.get("last_dispatch_error")).length()).isLessThanOrEqualTo(1000);
        assertThat(startCalls(journey)).isEqualTo(5);
        assertThat(enrollmentCount(profile)).isZero();

        // 放棄後不再被撿起
        clock.advance(Duration.ofHours(1));
        dispatcher.runOnce();
        assertThat(startCalls(journey)).isEqualTo(5);
        assertThat(enrollmentCount(profile)).isZero();
    }

    @Test
    void recoveryMidway_marksDispatched_andCreatesExactlyOneEnrollment() throws Exception {
        String eventName = newEventName();
        String journey = createJourney(eventName);
        failing.add(journey);
        armFailures();
        String profile = ulid();
        String eventId = ingestOk(eventName, profile);

        dispatcher.runOnce();
        advanceToDue(eventId);
        dispatcher.runOnce();
        assertThat(attempts(eventRow(eventId))).isEqualTo(2);
        assertThat(enrollmentCount(profile)).isZero();

        failing.clear(); // 綁定恢復
        advanceToDue(eventId);
        dispatcher.runOnce();

        Map<String, Object> r = eventRow(eventId);
        assertThat(r.get("dispatch_outcome")).isEqualTo("DISPATCHED");
        assertThat(instantOf(r.get("dispatched_at"))).isEqualTo(clock.instant());
        assertThat(enrollmentCount(profile, journey)).isEqualTo(1);

        // 再多跑幾輪也不重建
        clock.advance(Duration.ofHours(1));
        dispatcher.runOnce();
        dispatcher.runOnce();
        assertThat(enrollmentCount(profile, journey)).isEqualTo(1);
    }

    @Test
    void multiJourneyPartialFailure_keepsSuccessful_andGivesUpOnlyTheFailing() throws Exception {
        String eventName = newEventName();
        String okJourney = createJourney(eventName);
        String badJourney = createJourney(eventName);
        failing.add(badJourney);
        armFailures();
        String profile = ulid();
        String eventId = ingestOk(eventName, profile);

        dispatcher.runOnce();
        // 成功的綁定已保留，事件尚未標派發
        assertThat(enrollmentCount(profile, okJourney)).isEqualTo(1);
        assertThat(enrollmentCount(profile, badJourney)).isZero();
        assertThat(eventRow(eventId).get("dispatched_at")).isNull();
        assertThat(attempts(eventRow(eventId))).isEqualTo(1);

        for (int attempt = 2; attempt <= 5; attempt++) {
            advanceToDue(eventId);
            dispatcher.runOnce();
        }

        Map<String, Object> r = eventRow(eventId);
        assertThat(attempts(r)).isEqualTo(5);
        assertThat(r.get("dispatch_outcome")).isEqualTo("EXHAUSTED");
        assertThat(enrollmentCount(profile, okJourney)).as("成功的保留且不重建").isEqualTo(1);
        assertThat(enrollmentCount(profile, badJourney)).as("失敗的放棄").isZero();
    }

    @Test
    void multiJourneyPartialFailure_recovery_completesOnlyTheMissingBinding() throws Exception {
        String eventName = newEventName();
        String okJourney = createJourney(eventName);
        String badJourney = createJourney(eventName);
        failing.add(badJourney);
        armFailures();
        String profile = ulid();
        String eventId = ingestOk(eventName, profile);

        dispatcher.runOnce();
        assertThat(enrollmentCount(profile, okJourney)).isEqualTo(1);

        failing.clear();
        advanceToDue(eventId);
        dispatcher.runOnce();

        assertThat(eventRow(eventId).get("dispatch_outcome")).isEqualTo("DISPATCHED");
        assertThat(enrollmentCount(profile, okJourney)).isEqualTo(1);
        assertThat(enrollmentCount(profile, badJourney)).isEqualTo(1);
    }
}
