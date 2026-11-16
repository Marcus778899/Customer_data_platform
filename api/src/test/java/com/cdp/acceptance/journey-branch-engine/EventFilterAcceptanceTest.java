package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 驗收條件 3：入口 event_filter 不符合不建旅程進度；等事件 event_filter 不符合繼續等（D-085、D-087）。 */
class EventFilterAcceptanceTest extends BranchBase {

    private String entryJourney(String event, Object filter) throws Exception {
        return publish(def(Map.of(), List.of(start(), src(event, filter, "end_1"), end("end_1"))));
    }

    @Test
    void entryFilter_onlyMatchingEventsCreateEnrollment() throws Exception {
        String event = "evt_ef_" + uniq();
        entryJourney(event, leaf("properties.amount", "GT", 500));

        String hit = ulid();
        ingest(event, hit, props("amount", 600));
        assertThat(enrollmentCount(hit)).isEqualTo(1);
        assertThat(status(enrollmentOf(hit))).isEqualTo("COMPLETED");

        String low = ulid();
        ingest(event, low, props("amount", 400));
        assertThat(enrollmentCount(low)).as("金額不足").isZero();

        String boundary = ulid();
        ingest(event, boundary, props("amount", 500));
        assertThat(enrollmentCount(boundary)).as("GT 不含等於").isZero();

        String stringTyped = ulid();
        ingest(event, stringTyped, props("amount", "600"));
        assertThat(enrollmentCount(stringTyped)).as("型別不合即不成立，不報錯").isZero();

        String missing = ulid();
        ingest(event, missing, props());
        assertThat(enrollmentCount(missing)).as("沒有該屬性").isZero();

        String noProps = ulid();
        ingestAt(event, noProps, null, clock.instant());
        assertThat(enrollmentCount(noProps)).as("事件沒有 properties").isZero();
    }

    @Test
    void entryFilter_groupAndStringOperators() throws Exception {
        String event = "evt_efg_" + uniq();
        entryJourney(
                event,
                group(
                        "AND",
                        leaf("properties.amount", "GT", 500),
                        group("NOT", leaf("properties.channel", "EQ", "pos"))));
        String ok = ulid();
        ingest(event, ok, props("amount", 900, "channel", "web"));
        assertThat(enrollmentCount(ok)).isEqualTo(1);
        String pos = ulid();
        ingest(event, pos, props("amount", 900, "channel", "pos"));
        assertThat(enrollmentCount(pos)).isZero();
    }

    @Test
    void entryFilter_nullOrOmitted_meansNoFilter() throws Exception {
        String e1 = "evt_efn_" + uniq();
        entryJourney(e1, null);
        String p1 = ulid();
        ingest(e1, p1, props("amount", 1));
        assertThat(enrollmentCount(p1)).isEqualTo(1);

        String e2 = "evt_efo_" + uniq();
        publish(def(Map.of(), List.of(start(), src(e2, null, "end_1"), end("end_1"))));
        String p2 = ulid();
        ingest(e2, p2, props());
        assertThat(enrollmentCount(p2)).isEqualTo(1);
    }

    @Test
    void entryFilter_isPerJourney_otherJourneysOnSameEventStillEnroll() throws Exception {
        String event = "evt_efm_" + uniq();
        String strict = entryJourney(event, leaf("properties.amount", "GT", 500));
        String open = entryJourney(event, null);
        String p = ulid();
        ingest(event, p, props("amount", 10));
        List<String> journeys = jdbc.queryForList(
                "select journey_id from enrollment_record where profile_id = ?", String.class, p);
        assertThat(journeys).containsExactly(open).doesNotContain(strict);
    }

    @Test
    void waitFilter_mismatchKeepsWaiting_matchingEventWakes() throws Exception {
        String entry = "evt_wf_in_" + uniq();
        String wait = "evt_wf_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(entry, null, "wait_1"),
                        waitEvent(
                                "wait_1",
                                wait,
                                "PT1H",
                                leaf("properties.amount", "GT", 500),
                                Map.of("occurred", "end_occ", "timeout", "end_to")),
                        end("end_occ"),
                        end("end_to"))));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);
        assertThat(status(e)).isEqualTo("WAITING");

        advance(Duration.ofMinutes(10));
        ingest(wait, p, props("amount", 100));
        assertThat(status(e)).as("不符合篩選不算等到").isEqualTo("WAITING");
        assertThat(visits(e, "end_occ")).isZero();

        ingest(wait, p, props("amount", "900"));
        assertThat(status(e)).as("型別不合也不算等到").isEqualTo("WAITING");

        ingest(wait, p, props());
        assertThat(status(e)).as("缺屬性也不算等到").isEqualTo("WAITING");

        advance(Duration.ofMinutes(1));
        ingest(wait, p, props("amount", 900));
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(lastPort(e, "wait_1")).isEqualTo("occurred");
        assertThat(visits(e, "end_occ")).isEqualTo(1);
    }

    @Test
    void waitFilter_mismatchDoesNotExtendTimeout() throws Exception {
        String entry = "evt_wft_in_" + uniq();
        String wait = "evt_wft_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(entry, null, "wait_1"),
                        waitEvent(
                                "wait_1",
                                wait,
                                "PT1H",
                                leaf("properties.amount", "GT", 500),
                                Map.of("occurred", "end_occ", "timeout", "end_to")),
                        end("end_occ"),
                        end("end_to"))));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);

        advance(Duration.ofMinutes(30));
        ingest(wait, p, props("amount", 1));
        advance(Duration.ofMinutes(31));
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(lastPort(e, "wait_1")).as("逾時照原本的期限").isEqualTo("timeout");
        assertThat(visits(e, "end_to")).isEqualTo(1);
    }
}
