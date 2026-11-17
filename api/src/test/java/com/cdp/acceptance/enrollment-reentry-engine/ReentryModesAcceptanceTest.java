package com.cdp.acceptance.enrollmentreentryengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 驗收條件 1：五種 settings.reentry（journeys.md §4、spec 02 §6）。循序，經真實 HTTP 攝取與派發。 */
class ReentryModesAcceptanceTest extends ReentryBase {

    private static Map<String, Object> cart(String cartId) {
        return props("cart_id", cartId);
    }

    @Test
    void omitted_isUnlimited_everyEventEnrolls_dedupKeyNull() throws Exception {
        String ev = "evt_re_def_" + uniq();
        String j = quickJourney(ev, Map.of());
        String p = ulid();
        for (int i = 0; i < 3; i++) ingest(ev, p, cart("C-1"));
        assertThat(count(j, p)).isEqualTo(3);
        assertThat(dedupKeys(j, p)).containsOnlyNulls();
    }

    @Test
    void unlimited_explicit_everyEventEnrolls() throws Exception {
        String ev = "evt_re_unl_" + uniq();
        String j = quickJourney(ev, reentry("UNLIMITED", null, null));
        String p = ulid();
        for (int i = 0; i < 3; i++) ingest(ev, p, cart("C-1"));
        assertThat(count(j, p)).isEqualTo(3);
        assertThat(dedupKeys(j, p)).containsOnlyNulls();
    }

    @Test
    void once_onlyFirstEverEnrolls_evenAfterFirstTerminated_perProfile() throws Exception {
        String ev = "evt_re_once_" + uniq();
        String j = quickJourney(ev, reentry("ONCE", null, null));
        String p = ulid();
        ingest(ev, p, cart("C-1"));
        String first = enrollmentOf(p);
        assertThat(terminated(first)).isTrue();
        advance(Duration.ofDays(400));
        ingest(ev, p, cart("C-2"));
        ingest(ev, p, cart("C-3"));
        assertThat(count(j, p)).as("結束很久之後仍不再進入").isEqualTo(1);
        assertThat(dedupKeys(j, p)).containsExactly("__ONCE__");
        String other = ulid();
        ingest(ev, other, cart("C-1"));
        assertThat(count(j, other)).as("其他客戶不受影響").isEqualTo(1);
    }

    @Test
    void dedupKey_sameCartIdSentFiveTimes_createsOne() throws Exception {
        String ev = "evt_re_dk_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        for (int i = 0; i < 5; i++) ingest(ev, p, props("cart_id", "CART-99201", "n", i));
        assertThat(count(j, p)).as("同一 cart_id 送 5 次（5 個不同事件）只建 1 筆").isEqualTo(1);
        assertThat(dedupKeys(j, p)).containsExactly("CART-99201");
    }

    @Test
    void dedupKey_differentKeys_enrollSeparately_andKeyIsPerProfile() throws Exception {
        String ev = "evt_re_dk2_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        String q = ulid();
        ingest(ev, p, cart("A"));
        ingest(ev, p, cart("B"));
        ingest(ev, p, cart("A"));
        ingest(ev, q, cart("A"));
        assertThat(count(j, p)).isEqualTo(2);
        assertThat(dedupKeys(j, p)).containsExactlyInAnyOrder("A", "B");
        assertThat(count(j, q)).as("同鍵不同客戶各進各的").isEqualTo(1);
    }

    @Test
    void dedupKey_sameKeyStillBlocked_afterFirstTerminated_andLongAfter() throws Exception {
        String ev = "evt_re_dk3_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        ingest(ev, p, cart("A"));
        advance(Duration.ofDays(30));
        ingest(ev, p, cart("A"));
        assertThat(count(j, p)).isEqualTo(1);
    }

    @Test
    void dedupKey_missingNullOrEmpty_treatedAsUnlimited() throws Exception {
        String ev = "evt_re_dk4_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY", CART_PATH, null));
        String p = ulid();
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put("cart_id", null);
        ingest(ev, p, props("other", 1));
        ingest(ev, p, nullKey);
        ingest(ev, p, cart(""));
        assertThat(count(j, p)).as("抽不到鍵 = UNLIMITED，不擋人").isEqualTo(3);
        assertThat(dedupKeys(j, p)).containsOnlyNulls();
    }

    @Test
    void cooldown_blocksWithinCooldownAfterTermination_thenAllows_perProfile() throws Exception {
        String ev = "evt_re_cd_" + uniq();
        String j = quickJourney(ev, reentry("COOLDOWN", null, "P1D"));
        String p = ulid();
        ingest(ev, p, cart("A"));
        assertThat(count(j, p)).isEqualTo(1);
        advance(Duration.ofHours(23));
        ingest(ev, p, cart("B"));
        assertThat(count(j, p)).as("冷卻期內不進入").isEqualTo(1);
        advance(Duration.ofHours(2));
        ingest(ev, p, cart("C"));
        assertThat(count(j, p)).as("冷卻期過後可再進入").isEqualTo(2);
        assertThat(dedupKeys(j, p)).containsOnlyNulls();
        String other = ulid();
        ingest(ev, other, cart("A"));
        assertThat(count(j, other)).as("冷卻只看同一客戶").isEqualTo(1);
    }

    @Test
    void cooldown_unfinishedEnrollment_blocksEvenAfterCooldownElapsed() throws Exception {
        String ev = "evt_re_cd2_" + uniq();
        String j = longJourney(ev, reentry("COOLDOWN", null, "PT1H"));
        String p = ulid();
        ingest(ev, p, cart("A"));
        String first = enrollmentOf(p);
        assertThat(terminated(first)).isFalse();
        advance(Duration.ofDays(2));
        ingest(ev, p, cart("B"));
        assertThat(count(j, p)).as("有未結束的旅程進度就不再進入").isEqualTo(1);
    }

    @Test
    void cooldown_countsFromTerminatedAt_notEnrolledAt() throws Exception {
        String ev = "evt_re_cd3_" + uniq();
        String waitEnd = "evt_re_cd3_end_" + uniq();
        String j = publish(def(
                reentry("COOLDOWN", null, "PT1H"),
                List.of(
                        start(),
                        src(ev, null, "wait_1"),
                        waitEvent("wait_1", waitEnd, "P30D", null, Map.of("occurred", "end_1", "timeout", "end_1")),
                        end("end_1"))));
        String p = ulid();
        ingest(ev, p, cart("A"));
        advance(Duration.ofDays(5));
        ingest(waitEnd, p, props());
        assertThat(terminated(enrollmentOf(p))).isTrue();
        advance(Duration.ofMinutes(30));
        ingest(ev, p, cart("B"));
        assertThat(count(j, p)).as("自結束起算 30 分鐘仍在冷卻（若自進入起算早已過期）").isEqualTo(1);
        advance(Duration.ofMinutes(31));
        ingest(ev, p, cart("C"));
        assertThat(count(j, p)).isEqualTo(2);
    }

    @Test
    void dedupKeyWithCooldown_sameKeyNeverAgain_differentKeyOnlyAfterCooldown() throws Exception {
        String ev = "evt_re_dkc_" + uniq();
        String j = quickJourney(ev, reentry("DEDUP_KEY_WITH_COOLDOWN", CART_PATH, "P1D"));
        String p = ulid();
        ingest(ev, p, cart("A"));
        assertThat(count(j, p)).isEqualTo(1);
        advance(Duration.ofHours(1));
        ingest(ev, p, cart("B"));
        assertThat(count(j, p)).as("不同鍵但在冷卻期內").isEqualTo(1);
        advance(Duration.ofDays(2));
        ingest(ev, p, cart("A"));
        assertThat(count(j, p)).as("同鍵永遠只進一次").isEqualTo(1);
        ingest(ev, p, cart("B"));
        assertThat(count(j, p)).as("不同鍵、冷卻期已過").isEqualTo(2);
        assertThat(dedupKeys(j, p)).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void blockedEvent_isNotAnError_otherJourneysOnSameEventStillEnroll() throws Exception {
        String ev = "evt_re_multi_" + uniq();
        String once = quickJourney(ev, reentry("ONCE", null, null));
        String unlimited = quickJourney(ev, Map.of());
        String p = ulid();
        ingest(ev, p, cart("A"));
        ingest(ev, p, cart("B"));
        assertThat(count(once, p)).isEqualTo(1);
        assertThat(count(unlimited, p)).isEqualTo(2);
    }
}
