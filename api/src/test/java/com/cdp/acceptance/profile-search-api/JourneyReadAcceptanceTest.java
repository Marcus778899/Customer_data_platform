package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：journeys.md §2 旅程列表與唯讀旅程頁（第一版由 journey_definition 推導；D-050 不顯示人數）。 */
class JourneyReadAcceptanceTest extends ProfileStoreBase {

    private JsonNode list(String query) throws Exception {
        return getOk("/api/v1/journeys" + query);
    }

    @Test
    void list_summaryFields_derivedFromLatestVersion() throws Exception {
        String prefix = "列表-" + uniq();
        String id = publish(null, prefix + "-v1", "第一版描述", "等待A");
        clock.advance(Duration.ofHours(1));
        publish(id, prefix + "-v2", "第二版描述", "等待B");

        JsonNode items = list("?q=" + enc(prefix)).path("items");
        assertThat(items).hasSize(1);
        JsonNode s = items.get(0);
        assertThat(s.path("journey_id").asString()).isEqualTo(id);
        assertThat(s.path("name").asString()).as("取最新版本").isEqualTo(prefix + "-v2");
        assertThat(s.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(s.path("latest_version").asInt()).isEqualTo(2);
        assertThat(s.path("has_draft").asBoolean()).isFalse();
        assertThat(s.path("review_status").isNull()).isTrue();
        Instant t2 = T0.plus(Duration.ofHours(1));
        assertThat(Instant.parse(s.path("updated_at").asString())).isEqualTo(t2);
        assertThat(Instant.parse(s.path("published_at").asString())).isEqualTo(t2);

        // D-050：列表不顯示人數
        for (String k : (Iterable<String>) s.propertyNames()) {
            assertThat(k.toLowerCase()).as("不應有人數欄位").doesNotContain("count").doesNotContain("enrolled");
        }
    }

    @Test
    void list_qIsCaseInsensitiveContains_andFiltersAfterDerivation() throws Exception {
        String tag = "QzX" + uniq();
        String id = publish(null, "前綴-" + tag + "-後綴", null, "w");
        assertThat(field(list("?q=" + enc(tag.toLowerCase())).path("items"), "journey_id")).containsExactly(id);
        assertThat(field(list("?q=" + enc(tag.toUpperCase())).path("items"), "journey_id")).containsExactly(id);
        assertThat(list("?q=" + enc(tag + "-不存在")).path("items")).isEmpty();
    }

    @Test
    void list_statusFilter_commaSeparated() throws Exception {
        String tag = "St" + uniq();
        String id = publish(null, tag, null, "w");
        assertThat(field(list("?q=" + enc(tag) + "&status=ACTIVE").path("items"), "journey_id")).containsExactly(id);
        assertThat(field(list("?q=" + enc(tag) + "&status=DRAFT,ACTIVE").path("items"), "journey_id")).containsExactly(id);
        assertThat(list("?q=" + enc(tag) + "&status=DRAFT,ABORTED").path("items")).isEmpty();
    }

    @Test
    void list_newestUpdatedFirst_andCursorPaging() throws Exception {
        String tag = "Pg" + uniq();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(publish(null, tag + "-" + i, null, "w"));
            clock.advance(Duration.ofMinutes(1));
        }
        JsonNode all = list("?q=" + enc(tag));
        assertThat(field(all.path("items"), "journey_id")).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        assertThat(all.path("next_cursor").isNull()).isTrue();

        JsonNode p1 = list("?q=" + enc(tag) + "&limit=2");
        assertThat(field(p1.path("items"), "journey_id")).containsExactly(ids.get(2), ids.get(1));
        String cursor = p1.path("next_cursor").asString();
        assertThat(cursor).isNotBlank();
        JsonNode p2 = list("?q=" + enc(tag) + "&limit=2&cursor=" + enc(cursor));
        assertThat(field(p2.path("items"), "journey_id")).containsExactly(ids.get(0));
        assertThat(p2.path("next_cursor").isNull()).isTrue();
    }

    @Test
    void list_republishingMovesJourneyToTop() throws Exception {
        String tag = "Mv" + uniq();
        String a = publish(null, tag + "-a", null, "w");
        clock.advance(Duration.ofMinutes(1));
        String b = publish(null, tag + "-b", null, "w");
        clock.advance(Duration.ofMinutes(1));
        publish(a, tag + "-a", null, "w2");
        assertThat(field(list("?q=" + enc(tag)).path("items"), "journey_id")).containsExactly(a, b);
    }

    @Test
    void list_badParams_are400() throws Exception {
        assertProblem(get("/api/v1/journeys?limit=0"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/journeys?limit=101"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/journeys?status=BOGUS"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/journeys?cursor=garbage"), 400, "VALIDATION_FAILED");
        assertThat(get("/api/v1/journeys?limit=100").status()).isEqualTo(200);
    }

    @Test
    void detail_hasDescription_versionsNewestFirst_stoppedFieldsNull() throws Exception {
        String id = publish(null, "詳情-" + uniq(), "說明文字", "w1");
        clock.advance(Duration.ofHours(2));
        publish(id, "詳情-改名-" + uniq(), null, "w2");

        JsonNode d = getOk("/api/v1/journeys/" + id);
        assertThat(d.path("journey_id").asString()).isEqualTo(id);
        assertThat(d.path("name").asString()).startsWith("詳情-改名-");
        assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("latest_version").asInt()).isEqualTo(2);
        assertThat(d.path("has_draft").asBoolean()).isFalse();
        assertThat(d.path("review_status").isNull()).isTrue();
        assertThat(d.has("description")).isTrue();
        assertThat(d.path("description").isNull()).as("最新版本沒有描述").isTrue();
        JsonNode versions = d.path("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).path("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(versions.get(0).path("published_at").asString())).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(Instant.parse(versions.get(1).path("published_at").asString())).isEqualTo(T0);
        for (String f : List.of("stopped_at", "stop_reason", "stopped_by")) {
            assertThat(d.has(f)).as(f).isTrue();
            assertThat(d.path(f).isNull()).as(f).isTrue();
        }
    }

    @Test
    void detail_descriptionOfLatestVersion() throws Exception {
        String id = publish(null, "描述-" + uniq(), "最初描述", "w");
        assertThat(getOk("/api/v1/journeys/" + id).path("description").asString()).isEqualTo("最初描述");
    }

    @Test
    void detail_unknownJourney_is404() throws Exception {
        assertProblem(get("/api/v1/journeys/" + ulid()), 404, "JOURNEY_NOT_FOUND");
    }

    @Test
    void version_returnsThatVersionsDefinition_layoutNullForV010Versions() throws Exception {
        String id = publish(null, "版本-" + uniq(), "d1", "等待一");
        publish(id, "版本-" + uniq(), "d2", "等待二");

        JsonNode v1 = getOk("/api/v1/journeys/" + id + "/versions/1");
        JsonNode v2 = getOk("/api/v1/journeys/" + id + "/versions/2");
        assertThat(v1.path("journey_id").asString()).isEqualTo(id);
        assertThat(v1.path("version").asInt()).isEqualTo(1);
        assertThat(v2.path("version").asInt()).isEqualTo(2);
        assertThat(v1.path("description").asString()).isEqualTo("d1");
        assertThat(v2.path("description").asString()).isEqualTo("d2");
        assertThat(v1.path("entry_node").asString()).isEqualTo("start_1");
        assertThat(v1.path("settings").isObject()).isTrue();
        assertThat(v1.has("layout")).isTrue();
        assertThat(v1.path("layout").isNull()).isTrue();
        assertThat(Instant.parse(v1.path("published_at").asString())).isEqualTo(T0);

        JsonNode nodes = v1.path("nodes");
        assertThat(field(nodes, "id")).containsExactly("start_1", "src_1", "wait_1", "send_1", "end_1");
        JsonNode wait = nodes.get(2);
        assertThat(wait.path("type").asString()).isEqualTo("WaitDelayNode");
        assertThat(wait.path("name").asString()).isEqualTo("等待一");
        assertThat(wait.path("config").path("duration").asString()).isEqualTo("P3D");
        assertThat(wait.path("outputs").path("next").asString()).isEqualTo("send_1");
        assertThat(v2.path("nodes").get(2).path("name").asString()).isEqualTo("等待二");
    }

    @Test
    void version_unknownJourneyOrVersion_is404() throws Exception {
        String id = publish(null, "版本404-" + uniq(), null, "w");
        assertProblem(get("/api/v1/journeys/" + id + "/versions/99"), 404, "JOURNEY_NOT_FOUND");
        assertProblem(get("/api/v1/journeys/" + ulid() + "/versions/1"), 404, "JOURNEY_NOT_FOUND");
    }
}
