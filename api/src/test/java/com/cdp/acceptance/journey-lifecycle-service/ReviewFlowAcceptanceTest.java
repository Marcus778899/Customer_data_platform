package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** journeys.md §3.2、§3.3：送審、核准、退回、撤回、鎖定、不可核准自己送的、審核紀錄與 JourneySummary 欄位。 */
class ReviewFlowAcceptanceTest extends LifecycleBase {

    private String draftReady(Actor editor, String prefix) throws Exception {
        String id = newDraft(editor, prefix + uniq());
        save(editor, id, 1, sendDef(prefix, "evt_" + uniq(), "PT1H"));
        return id; // revision = 2
    }

    @Test
    void submit_createsPending_locksDraft_andShowsInSummary() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = draftReady(editor, "送審-");
        Resp r = submitRaw(editor, id, 2, "請審核");
        JsonNode rv = ok(r, 201);
        assertThat(rv.path("review_id").asString()).isNotBlank();
        assertThat(rv.path("journey_id").asString()).isEqualTo(id);
        assertThat(rv.path("action").asString()).isEqualTo("PUBLISH");
        assertThat(rv.path("status").asString()).isEqualTo("PENDING");
        assertThat(rv.path("revision").asInt()).isEqualTo(2);
        assertThat(rv.path("base_version").isNull()).isTrue();
        assertThat(rv.path("note").asString()).isEqualTo("請審核");
        assertThat(rv.path("submitted_by").path("user_id").asString()).isEqualTo(editor.userId());
        assertThat(rv.path("submitted_by").path("display_name").asString()).isEqualTo(editor.displayName());
        assertThat(instant(rv.path("submitted_at"))).isEqualTo(clock.instant());
        assertThat(rv.path("decided_by").isNull()).isTrue();
        assertThat(rv.path("decided_at").isNull()).isTrue();
        assertThat(rv.path("version").isNull()).isTrue();
        String rid = rv.path("review_id").asString();

        // 鎖定
        assertProblem(saveRaw(editor, id, 2, sendDef("x", "e", "PT1H")), 409, "DRAFT_IN_REVIEW");
        assertProblem(as(editor, "DELETE", "/api/v1/journeys/" + id + "/draft", null), 409, "DRAFT_IN_REVIEW");
        assertProblem(submitRaw(editor, id, 2, null), 409, "REVIEW_PENDING");

        // Draft.latest_review、Summary
        JsonNode draft = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);
        assertThat(draft.path("latest_review").path("review_id").asString()).isEqualTo(rid);
        assertThat(draft.path("latest_review").path("status").asString()).isEqualTo("PENDING");

        JsonNode detail = getDetail(id);
        assertThat(detail.path("review_status").asString()).isEqualTo("PENDING");
        JsonNode lr = detail.path("latest_review");
        assertThat(lr.path("review_id").asString()).isEqualTo(rid);
        assertThat(lr.path("status").asString()).isEqualTo("PENDING");
        assertThat(lr.path("submitted_by").path("display_name").asString()).isEqualTo(editor.displayName());
        assertThat(lr.path("submitted_at").asString()).isNotBlank();
        assertThat(lr.path("decided_by").isNull()).isTrue();

        // 待審清單篩選
        JsonNode pending = ok(as(admin, "GET", "/api/v1/journeys?review_status=PENDING&limit=100", null), 200).path("items");
        assertThat(field(pending, "journey_id")).contains(id);
        for (JsonNode s : pending) assertThat(s.path("review_status").asString()).isEqualTo("PENDING");

        // 審核紀錄
        JsonNode items = reviews(id);
        assertThat(field(items, "review_id")).containsExactly(rid);
    }

    @Test
    void submit_errors() throws Exception {
        Actor editor = actor("EDITOR");
        // 驗證失敗：骨架缺 Source
        String skeleton = newDraft(editor, "骨架-" + uniq());
        Resp bad = submitRaw(editor, skeleton, 1, null);
        assertProblem(bad, 400, "VALIDATION_FAILED");
        assertThat(bad.json().path("errors")).isNotEmpty();
        for (JsonNode e : bad.json().path("errors")) {
            assertThat(e.path("field").asString()).isNotBlank();
            assertThat(e.path("message").asString()).isNotBlank();
            assertThat(e.has("rule")).isTrue();
            assertThat(e.has("node_id")).isTrue();
        }
        assertThat(reviews(skeleton)).isEmpty();
        assertThat(getDetail(skeleton).path("review_status").isNull()).isTrue();

        String id = draftReady(editor, "錯誤-");
        assertProblem(submitRaw(editor, id, 1, null), 409, "DRAFT_CONFLICT");
        assertThat(submitRaw(editor, id, 2, "x".repeat(201)).status()).isEqualTo(400);
        assertProblem(submitRaw(editor, ulid(), 1, null), 404, "JOURNEY_NOT_FOUND");

        // 沒有草稿
        Actor admin = actor("ADMIN");
        String active = publishNew(editor, admin, sendDef("上線", "evt_" + uniq(), "PT1H"));
        assertProblem(submitRaw(editor, active, 1, null), 404, "DRAFT_NOT_FOUND");

        // 憑證限制：Bearer（含管理金鑰）不可送審、核准、退回、撤回
        assertProblem(key("POST", "/api/v1/journeys/" + id + "/draft/submit", Map.of("revision", 2)), 403, "FORBIDDEN");
        String rid = submit(editor, id, 2);
        for (String a : List.of("approve", "reject", "withdraw")) {
            assertProblem(
                    key("POST", "/api/v1/journeys/" + id + "/reviews/" + rid + "/" + a, Map.of("reason", "x")),
                    403,
                    "FORBIDDEN");
        }
        assertThat(reviews(id).get(0).path("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void approve_createsVersion_activates_clearsDraft() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String name = "核准-" + uniq();
        String id = newDraft(editor, name);
        Map<String, Object> d = sendDef(name, "evt_" + uniq(), "PT1H");
        int rev = save(editor, id, 1, d);
        String rid = submit(editor, id, rev);
        clock.advance(Duration.ofMinutes(10));

        JsonNode rv = ok(reviewAction(admin, id, rid, "approve", Map.of("reason", "OK")), 200);
        assertThat(rv.path("status").asString()).isEqualTo("APPROVED");
        assertThat(rv.path("version").asInt()).isEqualTo(1);
        assertThat(rv.path("reason").asString()).isEqualTo("OK");
        assertThat(rv.path("decided_by").path("user_id").asString()).isEqualTo(admin.userId());
        assertThat(instant(rv.path("decided_at"))).isEqualTo(clock.instant());

        JsonNode detail = getDetail(id);
        assertThat(detail.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(detail.path("latest_version").asInt()).isEqualTo(1);
        assertThat(detail.path("has_draft").asBoolean()).isFalse();
        assertThat(detail.path("review_status").isNull()).isTrue();
        assertThat(detail.path("latest_review").isNull()).isTrue();
        assertThat(detail.path("versions")).hasSize(1);
        assertThat(instant(detail.path("published_at"))).isEqualTo(clock.instant());
        assertProblem(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 404, "DRAFT_NOT_FOUND");

        JsonNode v1 = ok(key("GET", "/api/v1/journeys/" + id + "/versions/1", null), 200);
        assertThat(v1.path("name").asString()).isEqualTo(name);
        assertThat(v1.path("entry_node").asString()).isEqualTo("start");
        assertThat(v1.path("nodes")).hasSize(5);

        // 之後可以再核准一次？已終態
        assertProblem(reviewAction(admin, id, rid, "approve", Map.of()), 409, "REVIEW_NOT_PENDING");
        assertProblem(reviewAction(admin, id, rid, "reject", Map.of("reason", "晚了")), 409, "REVIEW_NOT_PENDING");
        assertProblem(reviewAction(editor, id, rid, "withdraw", Map.of()), 409, "REVIEW_NOT_PENDING");
    }

    @Test
    void approve_selfApproval_isForbidden_butOthersMay() throws Exception {
        Actor adminA = actor("ADMIN");
        Actor adminB = actor("ADMIN");
        String id = draftReady(adminA, "自審-");
        String rid = submit(adminA, id, 2);
        assertProblem(reviewAction(adminA, id, rid, "approve", Map.of()), 403, "SELF_APPROVAL_FORBIDDEN");
        assertThat(reviews(id).get(0).path("status").asString()).as("維持 PENDING").isEqualTo("PENDING");
        assertThat(getDetail(id).path("status").asString()).isEqualTo("DRAFT");

        // 退回、撤回不受限
        ok(reviewAction(adminA, id, rid, "withdraw", Map.of()), 200);
        int rev = 2;
        String rid2 = submit(adminA, id, rev);
        ok(reviewAction(adminA, id, rid2, "reject", Map.of("reason", "自己退自己")), 200);
        String rid3 = submit(adminA, id, rev);
        assertThat(ok(reviewAction(adminB, id, rid3, "approve", Map.of()), 200).path("status").asString())
                .isEqualTo("APPROVED");
    }

    @Test
    void approve_rolesAndUnknownReview() throws Exception {
        Actor editor = actor("EDITOR");
        Actor editor2 = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = draftReady(editor, "角色審-");
        String rid = submit(editor, id, 2);
        assertProblem(reviewAction(editor2, id, rid, "approve", Map.of()), 403, "FORBIDDEN");
        assertProblem(reviewAction(editor2, id, rid, "reject", Map.of("reason", "x")), 403, "FORBIDDEN");
        assertProblem(reviewAction(admin, id, ulid(), "approve", Map.of()), 404, "REVIEW_NOT_FOUND");
        assertProblem(reviewAction(admin, id, ulid(), "reject", Map.of("reason", "x")), 404, "REVIEW_NOT_FOUND");
        assertProblem(reviewAction(admin, id, ulid(), "withdraw", Map.of()), 404, "REVIEW_NOT_FOUND");
        assertProblem(
                as(admin, "GET", "/api/v1/journeys/" + ulid() + "/reviews", null), 404, "JOURNEY_NOT_FOUND");
    }

    @Test
    void reject_requiresReason_unlocksDraft_andAllowsResubmit() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = draftReady(editor, "退回-");
        String rid = submit(editor, id, 2);

        assertThat(reviewAction(admin, id, rid, "reject", Map.of()).status()).isEqualTo(400);
        assertThat(reviewAction(admin, id, rid, "reject", Map.of("reason", "")).status()).isEqualTo(400);
        assertThat(reviewAction(admin, id, rid, "reject", Map.of("reason", "x".repeat(201))).status()).isEqualTo(400);
        assertThat(reviews(id).get(0).path("status").asString()).isEqualTo("PENDING");

        JsonNode rv = ok(reviewAction(admin, id, rid, "reject", Map.of("reason", "等待時間請改為 2 天")), 200);
        assertThat(rv.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rv.path("reason").asString()).isEqualTo("等待時間請改為 2 天");
        assertThat(rv.path("decided_by").path("user_id").asString()).isEqualTo(admin.userId());
        assertThat(rv.path("version").isNull()).isTrue();

        JsonNode detail = getDetail(id);
        assertThat(detail.path("review_status").asString()).isEqualTo("REJECTED");
        assertThat(detail.path("latest_review").path("status").asString()).isEqualTo("REJECTED");
        assertThat(detail.path("latest_review").path("reason").asString()).isEqualTo("等待時間請改為 2 天");
        assertThat(detail.path("has_draft").asBoolean()).isTrue();

        // 草稿解鎖，內容與 revision 不變
        JsonNode draft = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);
        assertThat(draft.path("revision").asInt()).isEqualTo(2);
        int rev3 = save(editor, id, 2, sendDef("退回後", "evt_" + uniq(), "PT2H"));
        // 修改草稿後，REJECTED 仍是最新一筆審核且草稿仍在 -> 仍為 REJECTED；再送審建立新 Review
        String rid2 = submit(editor, id, rev3);
        assertThat(rid2).isNotEqualTo(rid);
        assertThat(getDetail(id).path("review_status").asString()).isEqualTo("PENDING");
        ok(reviewAction(admin, id, rid2, "approve", Map.of()), 200);

        // 紀錄：新到舊，保留退回那筆
        JsonNode items = reviews(id);
        assertThat(field(items, "status")).containsExactly("APPROVED", "REJECTED");
    }

    @Test
    void withdraw_bySubmitterOrAdmin_unlocksDraft() throws Exception {
        Actor editor = actor("EDITOR");
        Actor other = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = draftReady(editor, "撤回-");
        String rid = submit(editor, id, 2);
        assertProblem(reviewAction(other, id, rid, "withdraw", Map.of()), 403, "FORBIDDEN");
        JsonNode rv = ok(reviewAction(editor, id, rid, "withdraw", Map.of("reason", "改一下")), 200);
        assertThat(rv.path("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(rv.path("reason").asString()).isEqualTo("改一下");
        assertThat(rv.path("decided_by").path("user_id").asString()).isEqualTo(editor.userId());
        // 解鎖
        int rev = save(editor, id, 2, sendDef("撤回後", "evt_" + uniq(), "PT1H"));
        assertThat(getDetail(id).path("review_status").isNull()).as("撤回後不顯示審核狀態").isTrue();

        String rid2 = submit(editor, id, rev);
        JsonNode byAdmin = ok(reviewAction(admin, id, rid2, "withdraw", Map.of()), 200);
        assertThat(byAdmin.path("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(byAdmin.path("decided_by").path("user_id").asString()).isEqualTo(admin.userId());
    }

    @Test
    void secondVersion_keepsBaseVersion_andVersionsListNewestFirst() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("v1", ev, "PT1H"));
        clock.advance(Duration.ofHours(1));

        int rev = save(editor, id, 0, sendDef("v2名", ev, "PT2H"));
        assertThat(rev).isEqualTo(1);
        JsonNode rv = ok(submitRaw(editor, id, rev, null), 201);
        assertThat(rv.path("base_version").asInt()).isEqualTo(1);
        // 送審中旅程仍是 ACTIVE 且持續上線
        assertThat(getDetail(id).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(getDetail(id).path("review_status").asString()).isEqualTo("PENDING");

        JsonNode ap = ok(reviewAction(admin, id, rv.path("review_id").asString(), "approve", Map.of()), 200);
        assertThat(ap.path("version").asInt()).isEqualTo(2);
        JsonNode detail = getDetail(id);
        assertThat(detail.path("latest_version").asInt()).isEqualTo(2);
        assertThat(detail.path("name").asString()).isEqualTo("v2名");
        assertThat(detail.path("versions")).hasSize(2);
        assertThat(detail.path("versions").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(detail.path("versions").get(1).path("version").asInt()).isEqualTo(1);
        assertThat(ok(key("GET", "/api/v1/journeys/" + id + "/versions/1", null), 200).path("name").asString())
                .isEqualTo("v1");
    }

    @Test
    void reviewsList_pagesNewestFirst() throws Exception {
        Actor editor = actor("EDITOR");
        String id = draftReady(editor, "分頁-");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String rid = submit(editor, id, 2);
            ids.add(rid);
            ok(reviewAction(editor, id, rid, "withdraw", Map.of()), 200);
            clock.advance(Duration.ofMinutes(1));
        }
        JsonNode all = ok(key("GET", "/api/v1/journeys/" + id + "/reviews", null), 200);
        assertThat(field(all.path("items"), "review_id")).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        JsonNode p1 = ok(key("GET", "/api/v1/journeys/" + id + "/reviews?limit=2", null), 200);
        assertThat(p1.path("items")).hasSize(2);
        String cursor = p1.path("next_cursor").asString();
        assertThat(cursor).isNotBlank();
        JsonNode p2 = ok(key("GET", "/api/v1/journeys/" + id + "/reviews?limit=2&cursor=" + cursor, null), 200);
        assertThat(field(p2.path("items"), "review_id")).containsExactly(ids.get(0));
        assertThat(key("GET", "/api/v1/journeys/" + id + "/reviews?limit=0", null).status()).isEqualTo(400);
    }

    @Test
    void concurrentApprove_onlyOneWins() throws Exception {
        Actor editor = actor("EDITOR");
        Actor adminA = actor("ADMIN");
        Actor adminB = actor("ADMIN");
        String id = draftReady(editor, "並發-");
        String rid = submit(editor, id, 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Resp> a = () -> reviewAction(adminA, id, rid, "approve", Map.of());
            Callable<Resp> b = () -> reviewAction(adminB, id, rid, "approve", Map.of());
            Future<Resp> fa = pool.submit(a);
            Future<Resp> fb = pool.submit(b);
            Resp ra = fa.get();
            Resp rb = fb.get();
            List<Integer> codes = List.of(ra.status(), rb.status());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            Resp loser = ra.status() == 409 ? ra : rb;
            assertThat(loser.json().path("code").asString()).isEqualTo("REVIEW_NOT_PENDING");
        } finally {
            pool.shutdownNow();
        }
        assertThat(getDetail(id).path("latest_version").asInt()).isEqualTo(1);
        assertThat(getDetail(id).path("versions")).hasSize(1);
    }
}
