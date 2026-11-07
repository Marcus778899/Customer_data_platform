package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** journeys.md §3 草稿 API（建立、讀、存、刪）、角色（auth.md §2）與 JourneySummary 欄位。 */
class DraftApiAcceptanceTest extends LifecycleBase {

    @Test
    void createDraft_returnsSkeleton_andListShowsDraftSummary() throws Exception {
        Actor editor = actor("EDITOR");
        String name = "草稿-" + uniq();
        JsonNode r = ok(as(editor, "POST", "/api/v1/journeys/drafts", Map.of("name", name)), 201);
        String id = r.path("journey_id").asString();
        assertThat(id).isNotBlank();
        assertThat(r.path("status").asString()).isEqualTo("DRAFT");
        assertThat(r.path("revision").asInt()).isEqualTo(1);

        JsonNode d = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);
        assertThat(d.path("journey_id").asString()).isEqualTo(id);
        assertThat(d.path("revision").asInt()).isEqualTo(1);
        JsonNode def = d.path("definition");
        assertThat(def.path("name").asString()).isEqualTo(name);
        assertThat(def.path("description").isNull()).isTrue();
        assertThat(def.path("entry_node").asString()).isEqualTo("start");
        assertThat(def.path("nodes")).hasSize(1);
        assertThat(def.path("nodes").get(0).path("id").asString()).isEqualTo("start");
        assertThat(def.path("nodes").get(0).path("type").asString()).isEqualTo("StartNode");
        assertThat(d.path("layout").isNull()).isTrue();
        assertThat(d.path("latest_review").isNull()).isTrue();
        assertThat(d.path("updated_at").asString()).isNotBlank();

        JsonNode detail = getDetail(id);
        assertThat(detail.path("status").asString()).isEqualTo("DRAFT");
        assertThat(detail.path("latest_version").isNull()).isTrue();
        assertThat(detail.path("has_draft").asBoolean()).isTrue();
        assertThat(detail.path("review_status").isNull()).isTrue();
        assertThat(detail.path("latest_review").isNull()).isTrue();
        assertThat(detail.path("published_at").isNull()).isTrue();
        assertThat(detail.path("versions")).isEmpty();
        assertThat(detail.path("stopped_by").isNull()).isTrue();

        JsonNode items = ok(key("GET", "/api/v1/journeys?status=DRAFT&q=" + java.net.URLEncoder.encode(name, "UTF-8"), null), 200)
                .path("items");
        assertThat(field(items, "journey_id")).containsExactly(id);
    }

    @Test
    void createDraft_validation_andRoles() throws Exception {
        Actor editor = actor("EDITOR");
        Actor viewer = actor("VIEWER");
        assertThat(as(editor, "POST", "/api/v1/journeys/drafts", Map.of()).status()).isEqualTo(400);
        assertThat(as(editor, "POST", "/api/v1/journeys/drafts", Map.of("name", "")).status()).isEqualTo(400);
        assertProblem(as(viewer, "POST", "/api/v1/journeys/drafts", Map.of("name", "x")), 403, "FORBIDDEN");
        assertProblem(
                AuthHttp.bearer(port, "POST", "/api/v1/journeys/drafts", null, "{\"name\":\"x\"}"),
                401,
                "UNAUTHENTICATED");

        String id = newDraft(editor, "角色-" + uniq());
        // VIEWER 可讀草稿，不可寫
        assertThat(as(viewer, "GET", "/api/v1/journeys/" + id + "/draft", null).status()).isEqualTo(200);
        assertProblem(saveRaw(viewer, id, 1, sendDef("n", "e", "PT1H")), 403, "FORBIDDEN");
        assertProblem(as(viewer, "DELETE", "/api/v1/journeys/" + id + "/draft", null), 403, "FORBIDDEN");
        assertProblem(as(viewer, "POST", "/api/v1/journeys/" + id + "/draft/validate", null), 403, "FORBIDDEN");
        assertProblem(submitRaw(viewer, id, 1, null), 403, "FORBIDDEN");
    }

    @Test
    void saveDraft_incrementsRevision_recordsUpdatedBy_andKeepsLayout() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "存檔-" + uniq());
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", 1);
        b.put("definition", sendDef("存檔後", "evt_" + uniq(), "PT1H"));
        b.put("layout", Map.of("nodes", Map.of("start", Map.of("x", 0, "y", 0), "src_1", Map.of("x", 240, "y", 0))));
        JsonNode d = ok(as(editor, "PUT", "/api/v1/journeys/" + id + "/draft", b), 200);
        assertThat(d.path("revision").asInt()).isEqualTo(2);
        assertThat(d.path("updated_by").path("user_id").asString()).isEqualTo(editor.userId());
        assertThat(d.path("updated_by").path("display_name").asString()).isEqualTo(editor.displayName());

        JsonNode got = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);
        assertThat(got.path("revision").asInt()).isEqualTo(2);
        assertThat(got.path("definition").path("name").asString()).isEqualTo("存檔後");
        assertThat(got.path("definition").path("nodes")).hasSize(5);
        assertThat(got.path("layout").path("nodes").path("src_1").path("x").asInt()).isEqualTo(240);

        // 名稱屬於版本：存草稿不改旅程名稱以外的已發佈資料；摘要在 DRAFT 取草稿名稱
        assertThat(getDetail(id).path("name").asString()).isEqualTo("存檔後");
    }

    @Test
    void saveDraft_staleRevision_isDraftConflict_andNotFound() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "衝突-" + uniq());
        save(editor, id, 1, sendDef("v", "e", "PT1H")); // 現在 revision = 2
        assertProblem(saveRaw(editor, id, 1, sendDef("v", "e", "PT1H")), 409, "DRAFT_CONFLICT");
        assertThat(ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200).path("revision").asInt())
                .isEqualTo(2);

        assertProblem(saveRaw(editor, ulid(), 1, sendDef("v", "e", "PT1H")), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "GET", "/api/v1/journeys/" + ulid() + "/draft", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "DELETE", "/api/v1/journeys/" + ulid() + "/draft", null), 404, "JOURNEY_NOT_FOUND");
        assertProblem(as(editor, "POST", "/api/v1/journeys/" + ulid() + "/draft/validate", null), 404, "JOURNEY_NOT_FOUND");
    }

    @Test
    void saveDraft_structureLimits_return400() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "上限-" + uniq());

        assertThat(saveRaw(editor, id, 1, "字串不是物件").status()).isEqualTo(400);
        Map<String, Object> notArray = new LinkedHashMap<>(sendDef("n", "e", "PT1H"));
        notArray.put("nodes", Map.of("a", 1));
        assertThat(saveRaw(editor, id, 1, notArray).status()).isEqualTo(400);

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(node("n" + i, "EndNode", Map.of(), Map.of()));
        assertThat(saveRaw(editor, id, 1, def("多", many)).status()).as("201 個節點").isEqualTo(400);

        Map<String, Object> huge = new LinkedHashMap<>(sendDef("big", "e", "PT1H"));
        huge.put("settings", Map.of("junk", "x".repeat(300 * 1024)));
        assertThat(saveRaw(editor, id, 1, huge).status()).as("超過 256 KB").isEqualTo(400);

        // 200 個節點、結構正確但內容不合規則：存檔不擋（只驗結構）
        List<Map<String, Object>> exactly = new ArrayList<>();
        for (int i = 0; i < 200; i++) exactly.add(node("n" + i, "EndNode", Map.of(), Map.of()));
        assertThat(saveRaw(editor, id, 1, def("滿", exactly)).status()).isEqualTo(200);
    }

    @Test
    void saveDraft_onActiveJourney_withoutDraft_startsFromLatestVersion() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("上線版", ev, "PT2H"));

        JsonNode detail = getDetail(id);
        assertThat(detail.path("has_draft").asBoolean()).isFalse();
        assertProblem(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 404, "DRAFT_NOT_FOUND");
        // 沒有草稿時 DELETE 仍 204
        assertThat(as(editor, "DELETE", "/api/v1/journeys/" + id + "/draft", null).status()).isEqualTo(204);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("revision", 0);
        b.put("definition", sendDef("上線版", ev, "PT3H"));
        b.put("layout", null);
        JsonNode d = ok(as(editor, "PUT", "/api/v1/journeys/" + id + "/draft", b), 200);
        assertThat(d.path("revision").asInt()).isEqualTo(1);
        assertThat(getDetail(id).path("has_draft").asBoolean()).isTrue();
        // 已發佈版本不受影響（不可變）
        JsonNode v1 = ok(key("GET", "/api/v1/journeys/" + id + "/versions/1", null), 200);
        assertThat(v1.path("nodes").get(2).path("config").path("duration").asString()).isEqualTo("PT2H");
    }

    @Test
    void deleteDraft_rules() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String draftOnly = newDraft(editor, "刪-" + uniq());
        assertProblem(as(editor, "DELETE", "/api/v1/journeys/" + draftOnly + "/draft", null), 409, "DRAFT_REQUIRED");

        String id = publishNew(editor, admin, sendDef("刪草稿", "evt_" + uniq(), "PT1H"));
        save(editor, id, 0, sendDef("刪草稿", "evt_" + uniq(), "PT5H"));
        assertThat(as(editor, "DELETE", "/api/v1/journeys/" + id + "/draft", null).status()).isEqualTo(204);
        assertThat(as(editor, "DELETE", "/api/v1/journeys/" + id + "/draft", null).status()).isEqualTo(204);
        assertProblem(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 404, "DRAFT_NOT_FOUND");
        assertThat(getDetail(id).path("has_draft").asBoolean()).isFalse();
    }

    @Test
    void validate_doesNotChangeData_andReportsSkeletonIssues() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "驗證-" + uniq());
        JsonNode before = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);

        JsonNode rep = ok(as(editor, "POST", "/api/v1/journeys/" + id + "/draft/validate", null), 200);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        assertThat(rep.path("errors")).isNotEmpty();
        for (JsonNode e : rep.path("errors")) {
            assertThat(e.path("field").asString()).startsWith("definition");
            assertThat(e.path("message").asString()).isNotBlank();
            assertThat(e.has("rule")).isTrue();
            assertThat(e.has("node_id")).isTrue();
        }
        assertThat(rep.path("warnings").isArray()).isTrue();

        JsonNode after = ok(as(editor, "GET", "/api/v1/journeys/" + id + "/draft", null), 200);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void summary_updatedAtMovesWithDraftSave() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "時間-" + uniq());
        assertThat(instant(getDetail(id).path("updated_at"))).isEqualTo(clock.instant());
        clock.advance(java.time.Duration.ofMinutes(5));
        save(editor, id, 1, sendDef("時間", "e", "PT1H"));
        assertThat(instant(getDetail(id).path("updated_at"))).isEqualTo(clock.instant());
    }

    @Test
    void createAfterLockFreeEditors_unauthenticatedRead_is401() throws Exception {
        Resp r = AuthHttp.bearer(port, "GET", "/api/v1/journeys/" + ulid() + "/draft", null, null);
        assertThat(r.status()).isEqualTo(401);
    }
}
