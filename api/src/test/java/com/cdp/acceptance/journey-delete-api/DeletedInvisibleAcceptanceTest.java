package com.cdp.acceptance.journeydeleteapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** journeys.md §3.6 效果：已刪除旅程從所有讀寫路徑消失，審核紀錄列仍在 DB。 */
class DeletedInvisibleAcceptanceTest extends DeleteBase {

    @Test
    void afterDelete_everyEndpointIs404_listsHideIt_reviewRowsRemain() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");

        String id = newDraft(editor);
        String rid = submit(editor, id, save(editor, id, 1));
        ok(review(admin, id, rid, "reject", Map.of("reason", "退回")), 200);
        assertThat(listIds(editor, "review_status=REJECTED&")).contains(id);
        assertThat(delete(editor, id).status()).isEqualTo(204);

        String p = "/api/v1/journeys/" + id;
        assertProblem(key("GET", p, null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "GET", p, null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "GET", p + "/draft", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "GET", p + "/reviews", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(saveRaw(editor, id, 2), 404, "JOURNEY_NOT_FOUND");
        assertProblem(submitRaw(editor, id, 2), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "POST", p + "/draft/validate", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "DELETE", p + "/draft", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "POST", p + "/stop", Map.of("reason", "x")), 404, "JOURNEY_NOT_FOUND");
        assertProblem(review(admin, id, rid, "approve", Map.of()), 404, "REVIEW_NOT_FOUND");
        assertProblem(review(admin, id, rid, "reject", Map.of("reason", "x")), 404, "REVIEW_NOT_FOUND");
        assertProblem(review(editor, id, rid, "withdraw", Map.of()), 404, "REVIEW_NOT_FOUND");

        assertThat(listIds(editor, "")).doesNotContain(id);
        assertThat(listIds(editor, "status=DRAFT&")).doesNotContain(id);
        assertThat(listIds(editor, "review_status=REJECTED&")).doesNotContain(id);
        assertThat(listIds(admin, "review_status=REJECTED&")).doesNotContain(id);

        // 審核紀錄不刪
        assertThat(jdbc.queryForObject("select count(*) from journey_review where journey_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from journey_review where review_id = ? and status = 'REJECTED'",
                        Integer.class,
                        rid))
                .isEqualTo(1);
    }

    @Test
    void afterDelete_syncWithSameJourneyId_is404_andCreatesNothing() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor);
        assertThat(delete(editor, id).status()).isEqualTo(204);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journey_id", id);
        body.putAll(plainDef("同步-" + uniq(), "evt_" + uniq()));
        assertProblem(key("POST", "/api/v1/journeys", body), 404, "JOURNEY_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from journey_definition where journey_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("select status from journey where journey_id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }

    @Test
    void neverReviewedDraft_deletedAndHidden_otherJourneysUnaffected() throws Exception {
        Actor editor = actor("EDITOR");
        String gone = newDraft(editor);
        String kept = newDraft(editor);
        assertThat(listIds(editor, "")).contains(gone, kept);
        assertThat(delete(editor, gone).status()).isEqualTo(204);
        assertThat(listIds(editor, "")).contains(kept).doesNotContain(gone);
        assertThat(as(editor, "GET", "/api/v1/journeys/" + kept + "/draft", null).status()).isEqualTo(200);
    }
}
