package com.cdp.acceptance.journeydraftreviewscopeapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** journeys.md §1、§3「這份草稿的審核」、§3.1；spec 07 §12「V9」。 */
class DraftReviewScopeAcceptanceTest extends ScopeBase {

    private String rejectOnce(Actor editor, Actor admin, String id, int rev) throws Exception {
        String rid = submit(editor, id, rev);
        reviewAction(admin, id, rid, "reject", Map.of("reason", "請修改"));
        tick();
        return rid;
    }

    @Test
    void reject_deleteDraft_recreateViaPutRevision0_hasNoReview() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("二版", "evt_" + uniq(), "PT2H"));
        String rid = rejectOnce(editor, admin, id, rev);
        assertRejected(editor, id, rid);

        deleteDraft(editor, id);
        tick();
        save(editor, id, 0, plainDef("二版重建", "evt_" + uniq(), "PT2H"));

        assertNoReview(editor, id);
        assertThat(detail(id).path("has_draft").asBoolean()).isTrue();
    }

    @Test
    void recreatedDraft_ownSubmissionCountsNormally() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("A", "evt_" + uniq(), "PT2H"));
        rejectOnce(editor, admin, id, rev);
        deleteDraft(editor, id);
        tick();
        int rev2 = save(editor, id, 0, plainDef("B", "evt_" + uniq(), "PT2H"));
        assertNoReview(editor, id);

        String rid2 = submit(editor, id, rev2);
        assertThat(detail(id).path("review_status").asString()).isEqualTo("PENDING");
        assertThat(listIds(editor, "PENDING")).contains(id);
        assertThat(draft(editor, id).path("latest_review").path("review_id").asString())
                .isEqualTo(rid2);
    }

    @Test
    void reject_thenEditDraft_staysRejected() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("退回後改", "evt_" + uniq(), "PT2H"));
        String rid = rejectOnce(editor, admin, id, rev);

        save(editor, id, rev, plainDef("退回後改-2", "evt_" + uniq(), "PT3H"));

        assertRejected(editor, id, rid);
    }

    @Test
    void withdraw_deleteDraft_newDraftHasNoReview() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("撤回", "evt_" + uniq(), "PT2H"));
        String rid = submit(editor, id, rev);
        reviewAction(editor, id, rid, "withdraw", Map.of());
        tick();
        assertThat(detail(id).path("review_status").isNull()).isTrue();

        deleteDraft(editor, id);
        tick();
        save(editor, id, 0, plainDef("撤回後新", "evt_" + uniq(), "PT2H"));

        assertNoReview(editor, id);
    }

    @Test
    void reject_thenAdminKeyCreatesVersion_draftAndListStayRejected() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("金鑰干擾", "evt_" + uniq(), "PT2H"));
        String rid = rejectOnce(editor, admin, id, rev);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journey_id", id);
        body.putAll(plainDef("金鑰版本", "evt_" + uniq(), "PT4H"));
        ok(key("POST", "/api/v1/journeys", body), 201);
        tick();

        assertThat(detail(id).path("latest_version").asInt()).isEqualTo(2);
        assertRejected(editor, id, rid);
        assertThat(draft(editor, id).path("latest_review").path("review_id").asString())
                .isEqualTo(rid);
    }

    @Test
    void draftCreatedAt_setOnCreate_keptOnSave_nullAfterApproveOrDelete() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = newDraft(editor, "欄位-" + uniq());
        Instant created = createdAt(id);
        assertThat(created).isNotNull();
        int rev = save(editor, id, 1, plainDef("欄位", "evt_" + uniq(), "PT1H"));
        tick();
        rev = save(editor, id, rev, plainDef("欄位2", "evt_" + uniq(), "PT1H"));
        assertThat(createdAt(id)).isEqualTo(created);

        reviewAction(admin, id, submit(editor, id, rev), "approve", Map.of());
        assertThat(createdAt(id)).isNull();

        tick();
        save(editor, id, 0, plainDef("欄位3", "evt_" + uniq(), "PT1H"));
        assertThat(createdAt(id)).isNotNull();
        deleteDraft(editor, id);
        assertThat(createdAt(id)).isNull();
    }

    @Test
    void legacyDraftWithNullCreatedAt_behavesLikeV8() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        tick();
        int rev = save(editor, id, 0, plainDef("舊草稿", "evt_" + uniq(), "PT2H"));
        String rid = rejectOnce(editor, admin, id, rev);

        // 模擬 V9 之前建立的草稿：draft 有值、draft_created_at 為 null
        jdbc.update("update journey set draft_created_at = null where journey_id = ?", id);
        assertRejected(editor, id, rid);

        // 欄位為 null 時不以時間過濾：較早的審核紀錄也算（V8 行為）
        deleteDraft(editor, id);
        tick();
        save(editor, id, 0, plainDef("新", "evt_" + uniq(), "PT2H"));
        assertNoReview(editor, id);
        jdbc.update("update journey set draft_created_at = null where journey_id = ?", id);
        assertRejected(editor, id, rid);
    }

    private Instant createdAt(String id) {
        Timestamp t = jdbc.queryForObject(
                "select draft_created_at from journey where journey_id = ?", Timestamp.class, id);
        return t == null ? null : t.toInstant();
    }
}
