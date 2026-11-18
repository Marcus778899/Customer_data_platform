package com.cdp.acceptance.stopcancelrecordapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * spec 02 §6「冷卻期上限」：cooldown 不設上限；cutoff = 現在 - 冷卻期，早於 1970-01-01 或溢位時夾到該值
 * （等同「曾結束的都擋」），不再讓每次觸發都失敗而重試到 EXHAUSTED。
 */
class CooldownCapAcceptanceTest extends StopCancelBase {

    private static Map<String, Object> cooldown(String mode, String iso) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("mode", mode);
        r.put("cooldown", iso);
        if (mode.startsWith("DEDUP_KEY")) r.put("dedup_key_path", "$.trigger.properties.cart_id");
        return Map.of("reentry", r);
    }

    private String outcome(String eventId) {
        return jdbc.queryForObject(
                "select dispatch_outcome from event where event_id = ?", String.class, eventId);
    }

    private int attempts(String eventId) {
        return jdbc.queryForObject(
                "select dispatch_attempts from event where event_id = ?", Integer.class, eventId);
    }

    private void runHugeCooldown(String mode, String iso) throws Exception {
        String ev = "evt_cd_" + uniq();
        String j = publish(def(
                cooldown(mode, iso),
                List.of(start(), src(ev, "end_1"), end("end_1"))));
        String p = ulid();

        String first = ingest(ev, p, props("cart_id", "A"));
        assertThat(outcome(first)).as("第一次進入（沒有前次結束紀錄）").isEqualTo("DISPATCHED");
        assertThat(attempts(first)).as("沒有失敗重試").isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from enrollment_record where journey_id = ? and profile_id = ?",
                        Integer.class,
                        j,
                        p))
                .isEqualTo(1);

        clock.advance(Duration.ofDays(30));
        String second = ingest(ev, p, props("cart_id", "B"));
        assertThat(outcome(second)).as("被冷卻擋下不是錯誤：照常 DISPATCHED").isEqualTo("DISPATCHED");
        assertThat(attempts(second)).as("不重試").isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from enrollment_record where journey_id = ? and profile_id = ?",
                        Integer.class,
                        j,
                        p))
                .as("曾結束的都擋").isEqualTo(1);

        // 再跑幾輪派發，也不會被重試成 EXHAUSTED
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(1));
            dispatcher.runOnce();
        }
        assertThat(outcome(second)).isEqualTo("DISPATCHED");
        assertThat(attempts(second)).isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from event where dispatch_outcome = 'EXHAUSTED' and event_id in (?, ?)",
                        Integer.class,
                        first,
                        second))
                .isZero();
    }

    @Test
    void cooldown_century_stillWorks() throws Exception {
        runHugeCooldown("COOLDOWN", "P36500D");
    }

    @Test
    void cooldown_cutoffBefore1970_isClamped_blocksInsteadOfFailing() throws Exception {
        runHugeCooldown("COOLDOWN", "P100000000D");
    }

    @Test
    void cooldown_maxDuration_overflowIsClamped() throws Exception {
        runHugeCooldown("COOLDOWN", "P106751991167300D");
    }

    @Test
    void dedupKeyWithCooldown_hugeCooldown_isClamped() throws Exception {
        runHugeCooldown("DEDUP_KEY_WITH_COOLDOWN", "P100000000D");
    }
}
