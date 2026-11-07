package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** journeys.md §3.1 旅程列同步、§3.3 第 3 列（D-061，開關預設 true）：管理金鑰建立版本多一筆「管理金鑰建立」紀錄。 */
class AdminKeyCreateEnabledAcceptanceTest extends LifecycleBase {

    private static Map<String, Object> body(String journeyId, Map<String, Object> def) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (journeyId != null) m.put("journey_id", journeyId);
        m.putAll(def);
        return m;
    }

    @Test
    void newJourney_writesAdminKeyReview_andJourneyRow() throws Exception {
        String name = "金鑰-" + uniq();
        JsonNode created = ok(key("POST", "/api/v1/journeys", body(null, sendDef(name, "evt_" + uniq(), "PT1H"))), 201);
        String id = created.path("journey_id").asString();

        JsonNode detail = getDetail(id);
        assertThat(detail.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(detail.path("latest_version").asInt()).isEqualTo(1);
        assertThat(detail.path("has_draft").asBoolean()).isFalse();
        assertThat(detail.path("review_status").isNull()).isTrue();

        JsonNode items = reviews(id);
        assertThat(items).hasSize(1);
        JsonNode r = items.get(0);
        assertThat(r.path("action").asString()).isEqualTo("PUBLISH");
        assertThat(r.path("status").asString()).isEqualTo("APPROVED");
        assertThat(r.path("revision").isNull()).isTrue();
        assertThat(r.path("submitted_by").isNull()).isTrue();
        assertThat(r.path("decided_by").isNull()).isTrue();
        assertThat(r.path("reason").asString()).isEqualTo("管理金鑰建立");
        assertThat(r.path("version").asInt()).isEqualTo(1);
        assertThat(r.path("submitted_at").asString()).isNotBlank();
        assertThat(instant(r.path("submitted_at"))).isEqualTo(instant(r.path("decided_at")));
        assertThat(instant(r.path("decided_at"))).isEqualTo(clock.instant());
    }

    @Test
    void secondVersionViaKey_updatesLatestVersion_andAddsAnotherRecord() throws Exception {
        String ev = "evt_" + uniq();
        String id = ok(key("POST", "/api/v1/journeys", body(null, sendDef("金鑰二版-" + uniq(), ev, "PT1H"))), 201)
                .path("journey_id").asString();
        ok(key("POST", "/api/v1/journeys", body(id, sendDef("金鑰二版-新", ev, "PT2H"))), 201);
        assertThat(getDetail(id).path("latest_version").asInt()).isEqualTo(2);
        assertThat(reviews(id)).hasSize(2);
        assertThat(field(reviews(id), "version")).containsExactly("2", "1");
    }

    @Test
    void existingDraftJourney_viaKey_becomesActiveV1_keepsDraft_andPendingReview() throws Exception {
        Actor editor = actor("EDITOR");
        String ev = "evt_" + uniq();
        String id = newDraft(editor, "草稿被金鑰發佈-" + uniq());
        int rev = save(editor, id, 1, sendDef("草稿內容", ev, "PT1H"));
        String rid = submit(editor, id, rev);

        ok(key("POST", "/api/v1/journeys", body(id, sendDef("金鑰版本", ev, "PT3H"))), 201);
        JsonNode d = getDetail(id);
        assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("latest_version").asInt()).isEqualTo(1);
        assertThat(d.path("has_draft").asBoolean()).as("草稿保留").isTrue();
        assertThat(d.path("review_status").asString()).as("送審中的 Review 不受影響").isEqualTo("PENDING");
        assertThat(ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200).path("revision").asInt())
                .isEqualTo(rev);

        // 之後核准的草稿成為再下一版
        Actor admin = actor("ADMIN");
        JsonNode ap = ok(reviewAction(admin, id, rid, "approve", Map.of()), 200);
        assertThat(ap.path("version").asInt()).isEqualTo(2);
    }

    @Test
    void unknownJourneyId_is404_andNonAdminCredentialsAre403() throws Exception {
        assertProblem(key("POST", "/api/v1/journeys", body(ulid(), sendDef("x", "e", "PT1H"))), 404, "JOURNEY_NOT_FOUND");
        Actor admin = actor("ADMIN");
        assertProblem(as(admin, "POST", "/api/v1/journeys", body(null, sendDef("x", "e", "PT1H"))), 403, "FORBIDDEN");
    }

    @Test
    void fieldKeys_listsAttributesAndEventProperties_sorted() throws Exception {
        String ev = "evt_" + uniq();
        String attr = "zz_attr_" + uniq().substring(0, 6);
        // 客戶屬性
        var p = com.cdp.acceptance.security.AuthHttp.bearer(
                port,
                "PUT",
                "/api/v1/profiles/" + ulid(),
                com.cdp.acceptance.security.AuthHttp.INGEST_KEY,
                js(Map.of("attributes", Map.of(attr, "v", "aa_tier", "gold"))));
        assertThat(p.status()).as(p.body()).isEqualTo(200);
        ingest(ev, ulid(), Map.of("zk", 1, "ak", 2));

        Actor viewer = actor("VIEWER");
        JsonNode r = ok(as(viewer, "GET", "/api/v1/journeys/field-keys?event_name=" + ev, null), 200);
        java.util.List<String> props = new java.util.ArrayList<>();
        for (JsonNode n : r.path("event_properties")) props.add(n.asString());
        assertThat(props).containsExactly("ak", "zk");
        java.util.List<String> attrs = new java.util.ArrayList<>();
        for (JsonNode n : r.path("profile_attributes")) attrs.add(n.asString());
        assertThat(attrs).contains("aa_tier", attr).isSorted();
        assertThat(r.path("as_of").asString()).isNotBlank();

        JsonNode none = ok(as(viewer, "GET", "/api/v1/journeys/field-keys", null), 200);
        assertThat(none.path("event_properties")).isEmpty();
    }
}
