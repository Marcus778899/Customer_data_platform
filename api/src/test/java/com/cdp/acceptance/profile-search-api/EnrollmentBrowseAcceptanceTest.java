package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：journey-engine.md §4 {@code GET /enrollments} 省略 q 的瀏覽（D-030）、journey_id 篩選、分頁。 */
class EnrollmentBrowseAcceptanceTest extends ProfileStoreBase {

    @Test
    void browse_withoutQ_isAllowed_newestFirst_scopedByJourneyId() throws Exception {
        String j = publish(null, "瀏覽-" + uniq(), null, "等待");
        String p1 = ulid();
        String p2 = ulid();
        String e1 = enrollment(j, 1, p1, "WAITING", T0.minusSeconds(300));
        String e2 = enrollment(j, 1, p2, "COMPLETED", T0.minusSeconds(200));
        String e3 = enrollment(j, 1, p1, "WAITING", T0.minusSeconds(100));

        JsonNode r = getOk("/api/v1/enrollments?journey_id=" + j);
        assertThat(field(r.path("items"), "enrollment_id")).containsExactly(e3, e2, e1);
        assertThat(r.path("next_cursor").isNull()).isTrue();
        JsonNode s = r.path("items").get(0);
        for (String f : List.of("enrollment_id", "journey_id", "profile_id", "current_node_id", "current_node_type", "journey_version", "journey_name", "current_node_name", "status", "enrolled_at", "updated_at", "terminated_at")) {
            assertThat(s.has(f)).as(f).isTrue();
        }
        assertThat(s.path("journey_name").asString()).startsWith("瀏覽-");
    }

    @Test
    void browse_statusFilter_andOtherJourneysExcluded() throws Exception {
        String j = publish(null, "瀏覽狀態-" + uniq(), null, "等待");
        String other = publish(null, "瀏覽他旅程-" + uniq(), null, "等待");
        String w = enrollment(j, 1, ulid(), "WAITING", T0.minusSeconds(20));
        enrollment(j, 1, ulid(), "COMPLETED", T0.minusSeconds(10));
        enrollment(other, 1, ulid(), "WAITING", T0.minusSeconds(5));

        assertThat(field(getOk("/api/v1/enrollments?journey_id=" + j + "&status=WAITING").path("items"), "enrollment_id"))
                .containsExactly(w);
        assertThat(getOk("/api/v1/enrollments?journey_id=" + j + "&status=WAITING,COMPLETED").path("items")).hasSize(2);
        assertThat(getOk("/api/v1/enrollments?journey_id=" + ulid()).path("items")).isEmpty();
    }

    @Test
    void browse_overallRecent_isOrderedByEnrolledAtDescThenIdDesc() throws Exception {
        String j = publish(null, "瀏覽全域-" + uniq(), null, "等待");
        Instant tie = T0.plusSeconds(7);
        for (int i = 0; i < 4; i++) enrollment(j, 1, ulid(), "WAITING", tie);
        enrollment(j, 1, ulid(), "WAITING", tie.plusSeconds(1));

        JsonNode items = getOk("/api/v1/enrollments?limit=100").path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(5);
        for (int i = 1; i < items.size(); i++) {
            Instant prev = Instant.parse(items.get(i - 1).path("enrolled_at").asString());
            Instant cur = Instant.parse(items.get(i).path("enrolled_at").asString());
            assertThat(prev).isAfterOrEqualTo(cur);
            if (prev.equals(cur)) {
                assertThat(items.get(i - 1).path("enrollment_id").asString())
                        .isGreaterThan(items.get(i).path("enrollment_id").asString());
            }
        }
    }

    @Test
    void browse_cursorPaging_walksEverythingOnce_withTies() throws Exception {
        String j = publish(null, "瀏覽分頁-" + uniq(), null, "等待");
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            expected.add(enrollment(j, 1, ulid(), "WAITING", T0.plusSeconds(i / 3))); // 三筆一組同時間
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode r = getOk("/api/v1/enrollments?journey_id=" + j + "&limit=2" + (cursor == null ? "" : "&cursor=" + enc(cursor)));
            seen.addAll(field(r.path("items"), "enrollment_id"));
            cursor = r.path("next_cursor").isNull() ? null : r.path("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 20);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(expected);
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void browse_stillSupportsExactQ() throws Exception {
        String j = publish(null, "瀏覽q-" + uniq(), null, "等待");
        String p = ulid();
        String e = enrollment(j, 1, p, "WAITING", T0);
        enrollment(j, 1, ulid(), "WAITING", T0);
        assertThat(field(getOk("/api/v1/enrollments?q=" + p).path("items"), "enrollment_id")).containsExactly(e);
        assertThat(field(getOk("/api/v1/enrollments?q=" + e).path("items"), "enrollment_id")).containsExactly(e);
        assertThat(getOk("/api/v1/enrollments?q=" + ulid()).path("items")).isEmpty();
    }

    @Test
    void browse_badParams_are400() throws Exception {
        assertProblem(get("/api/v1/enrollments?limit=0"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/enrollments?limit=101"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/enrollments?status=BOGUS"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/enrollments?cursor=garbage"), 400, "VALIDATION_FAILED");
    }
}
