package com.cdp.acceptance.journeydeleteapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp;

/** journeys.md §3.6 DELETE /journeys/{id}：角色、錯誤碼與檢查順序、DRAFT_REQUIRED 不變。 */
class DeleteJourneyAcceptanceTest extends DeleteBase {

    private boolean notDeleted(String id) {
        return jdbc.queryForObject("select deleted_at is null from journey where journey_id = ?", Boolean.class, id);
    }

    @Test
    void editorAndAdmin_canDeleteNeverPublished_204_andRecordsDeleter() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String a = newDraft(editor);
        String b = newDraft(editor);
        assertThat(delete(editor, a).status()).isEqualTo(204);
        assertThat(delete(admin, b).status()).isEqualTo(204); // 不限建立者

        assertThat(jdbc.queryForObject("select deleted_by from journey where journey_id = ?", String.class, a))
                .isEqualTo(editor.userId());
        assertThat(jdbc.queryForObject("select deleted_by from journey where journey_id = ?", String.class, b))
                .isEqualTo(admin.userId());
        assertThat(notDeleted(a)).isFalse();
    }

    @Test
    void viewer_403_bearerKey_403_anonymous_401_andNotDeleted() throws Exception {
        Actor editor = actor("EDITOR");
        Actor viewer = actor("VIEWER");
        String id = newDraft(editor);
        assertProblem(delete(viewer, id), 403, "FORBIDDEN");
        assertThat(key("DELETE", "/api/v1/journeys/" + id, null).status()).isEqualTo(403);
        assertThat(AuthHttp.bearer(port, "DELETE", "/api/v1/journeys/" + id, null, null).status())
                .isEqualTo(401);
        assertThat(notDeleted(id)).isTrue();
        assertThat(delete(editor, id).status()).isEqualTo(204);
    }

    @Test
    void unknownAndAlreadyDeleted_404() throws Exception {
        Actor editor = actor("EDITOR");
        assertProblem(delete(editor, ulid()), 404, "JOURNEY_NOT_FOUND");
        String id = newDraft(editor);
        assertThat(delete(editor, id).status()).isEqualTo(204);
        assertProblem(delete(editor, id), 404, "JOURNEY_NOT_FOUND");
    }

    @Test
    void activeAndAborted_409_JOURNEY_PUBLISHED_andStayVisible() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String active = publishNew(editor, admin);
        assertProblem(delete(editor, active), 409, "JOURNEY_PUBLISHED");

        String aborted = publishNew(editor, admin);
        ok(as(editor, "POST", "/api/v1/journeys/" + aborted + "/stop", Map.of("reason", "停")), 200);
        assertProblem(delete(admin, aborted), 409, "JOURNEY_PUBLISHED");

        for (String id : new String[] {active, aborted}) {
            assertThat(ok(key("GET", "/api/v1/journeys/" + id, null), 200).path("journey_id").asString())
                    .isEqualTo(id);
            assertThat(notDeleted(id)).isTrue();
        }
    }

    @Test
    void publishedWithNewDraft_stillPublished409() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin);
        save(editor, id, 0);
        assertProblem(delete(editor, id), 409, "JOURNEY_PUBLISHED");
    }

    @Test
    void inReview_409_DRAFT_IN_REVIEW_thenWithdrawOrRejectAllowsDelete() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");

        String w = newDraft(editor);
        String wr = submit(editor, w, save(editor, w, 1));
        assertProblem(delete(editor, w), 409, "DRAFT_IN_REVIEW");
        assertProblem(delete(admin, w), 409, "DRAFT_IN_REVIEW");
        assertThat(notDeleted(w)).isTrue();
        ok(review(editor, w, wr, "withdraw", Map.of()), 200);
        assertThat(delete(editor, w).status()).isEqualTo(204);

        String r = newDraft(editor);
        String rr = submit(editor, r, save(editor, r, 1));
        ok(review(admin, r, rr, "reject", Map.of("reason", "改")), 200);
        assertThat(delete(editor, r).status()).isEqualTo(204);
    }

    @Test
    void deleteDraftEndpoint_onDraftStatus_stillDraftRequired() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor);
        assertProblem(as(editor, "DELETE", "/api/v1/journeys/" + id + "/draft", null), 409, "DRAFT_REQUIRED");
        assertThat(notDeleted(id)).isTrue();
        assertThat(delete(editor, id).status()).isEqualTo(204);
    }
}
