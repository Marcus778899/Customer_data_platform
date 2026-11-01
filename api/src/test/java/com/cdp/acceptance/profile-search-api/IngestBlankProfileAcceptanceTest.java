package com.cdp.acceptance.profilesearchapi;

import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：profiles.md §2 末條——事件攝取遇到不存在的 profile_id 自動建空白客戶（{@code on conflict do nothing}）。 */
class IngestBlankProfileAcceptanceTest extends ProfileStoreBase {

    private Map<String, Object> event(String profileId) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event_id", ulid());
        ev.put("source_type", "CUSTOMER_BEHAVIOR");
        ev.put("source_id", "web-sdk@2.1.0");
        ev.put("name", "psa_unmatched_" + uniq());
        ev.put("occurred_at", clock.instant().minusSeconds(60).toString());
        ev.put("identity", Map.of("profile_id", profileId));
        ev.put("properties", Map.of("k", "v"));
        return ev;
    }

    @Test
    void ingest_unknownProfile_createsBlankProfile() throws Exception {
        String p = ulid();
        assertProblem(get("/api/v1/profiles/" + p), 404, "PROFILE_NOT_FOUND");
        Resp r = post("/api/v1/events", INGEST_KEY, event(p));
        assertThat(r.status()).as(r.body()).isEqualTo(202);

        JsonNode d = getOk("/api/v1/profiles/" + p);
        assertThat(d.path("profile_id").asString()).isEqualTo(p);
        assertThat(d.path("attributes").size()).isZero();
        assertThat(d.path("identifiers")).isEmpty();
        assertThat(Instant.parse(d.path("created_at").asString())).isEqualTo(T0);
    }

    @Test
    void ingest_existingProfile_isLeftUntouched_includingUpdatedAt() throws Exception {
        String p = ulid();
        String u = uniq();
        putOk(p, body(Map.of("name", "Keep"), List.of(ident("MEMBER_ID", "K-" + u))));
        clock.advance(Duration.ofMinutes(30));
        assertThat(post("/api/v1/events", INGEST_KEY, event(p)).status()).isEqualTo(202);

        JsonNode d = getOk("/api/v1/profiles/" + p);
        assertThat(d.path("attributes").path("name").asString()).isEqualTo("Keep");
        assertThat(d.path("identifiers")).hasSize(1);
        assertThat(Instant.parse(d.path("updated_at").asString())).isEqualTo(T0);
    }

    @Test
    void ingest_duplicateEvent_isStillFine_andProfileExistsOnce() throws Exception {
        String p = ulid();
        Map<String, Object> ev = event(p);
        assertThat(post("/api/v1/events", INGEST_KEY, ev).status()).isEqualTo(202);
        Resp again = post("/api/v1/events", INGEST_KEY, ev);
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.json().path("status").asString()).isEqualTo("DUPLICATE");
        Integer n = jdbc.queryForObject("select count(*) from profile where profile_id = ?", Integer.class, p);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void ingestBatch_createsBlankProfilesForEachUnknownProfile() throws Exception {
        String a = ulid();
        String b = ulid();
        Map<String, Object> batch = Map.of("events", List.of(event(a), event(b), event(a)));
        Resp r = post("/api/v1/events/batch", INGEST_KEY, batch);
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        assertThat(getOk("/api/v1/profiles/" + a).path("profile_id").asString()).isEqualTo(a);
        assertThat(getOk("/api/v1/profiles/" + b).path("profile_id").asString()).isEqualTo(b);
    }

    @Test
    void ingest_rejectedEvent_doesNotCreateProfile() throws Exception {
        String p = ulid();
        Map<String, Object> bad = event(p);
        bad.put("name", "Bad Name!"); // 不符 ^[a-z][a-z0-9_]*$
        Resp r = post("/api/v1/events", INGEST_KEY, bad);
        assertProblem(r, 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/profiles/" + p), 404, "PROFILE_NOT_FOUND");
    }

    @Test
    void ingest_concurrentEventsForSameNewProfile_allAccepted_profileCreatedOnce() throws Exception {
        String p = ulid();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> ev = event(p);
                Callable<Resp> c =
                        () -> {
                            go.await();
                            return post("/api/v1/events", INGEST_KEY, ev);
                        };
                fs.add(pool.submit(c));
            }
            go.countDown();
            for (Future<Resp> f : fs) assertThat(f.get().status()).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }
        Integer cnt = jdbc.queryForObject("select count(*) from profile where profile_id = ?", Integer.class, p);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    void blankProfileCreatedByIngest_canBeFilledByPut() throws Exception {
        String p = ulid();
        String u = uniq();
        assertThat(post("/api/v1/events", INGEST_KEY, event(p)).status()).isEqualTo(202);
        JsonNode d = putOk(p, body(Map.of("name", "Late"), List.of(ident("MEMBER_ID", "L-" + u))));
        assertThat(d.path("attributes").path("name").asString()).isEqualTo("Late");
        assertThat(d.path("identifiers")).hasSize(1);
    }
}
