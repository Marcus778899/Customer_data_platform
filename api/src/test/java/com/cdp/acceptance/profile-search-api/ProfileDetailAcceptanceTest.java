package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：profiles.md §3 客戶頁資料（{@code GET /profiles/{id}}）、遮罩白名單、客戶的 Enrollment 列表。 */
class ProfileDetailAcceptanceTest extends ProfileStoreBase {

    @Test
    void detail_masksByWhitelist_identifiersByTypeKey_attributesByKey() throws Exception {
        String id = ulid();
        String u = uniq();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", "王小美"); // 白名單：明文
        attrs.put("tier", 3); // 白名單：明文
        attrs.put("city", Map.of("zip", "100", "area", "中正區")); // 白名單：整棵子樹明文
        attrs.put("birthday", "1990-01-01"); // 非白名單：遮罩
        attrs.put("contact_email", "amy." + u + "@example.com"); // 非白名單、email 形狀
        attrs.put("score", 99); // 非白名單：數字也遮罩
        attrs.put("vip", true);
        attrs.put("spouse", Map.of("phone", "0912")); // 非白名單物件：往下遮罩，鍵名保留
        putOk(
                id,
                body(
                        attrs,
                        List.of(
                                ident("EMAIL", "amy." + u + "@example.com"),
                                ident("MEMBER_ID", "M-" + u),
                                ident("LINE_USER_ID", "U" + u))));

        JsonNode d = getOk("/api/v1/profiles/" + id);
        JsonNode a = d.path("attributes");
        assertThat(a.path("name").asString()).isEqualTo("王小美");
        assertThat(a.path("tier").asInt()).isEqualTo(3);
        assertThat(a.path("city").path("zip").asString()).isEqualTo("100");
        assertThat(a.path("city").path("area").asString()).isEqualTo("中正區");
        assertThat(a.path("birthday").asString()).isEqualTo("***");
        assertThat(a.path("contact_email").asString()).isEqualTo("a***@example.com");
        assertThat(a.path("score").asString()).isEqualTo("***");
        assertThat(a.path("vip").asString()).isEqualTo("***");
        assertThat(a.path("spouse").path("phone").asString()).isEqualTo("***");

        // 識別碼：鍵名 = type 小寫，在白名單才明文（member_id 在、email／line_user_id 不在）
        JsonNode ids = d.path("identifiers");
        assertThat(field(ids, "type")).containsExactly("EMAIL", "LINE_USER_ID", "MEMBER_ID");
        assertThat(ids.get(0).path("value").asString()).isEqualTo("a***@example.com");
        assertThat(ids.get(0).path("masked").asBoolean()).isTrue();
        assertThat(ids.get(1).path("value").asString()).isEqualTo("***");
        assertThat(ids.get(1).path("masked").asBoolean()).isTrue();
        assertThat(ids.get(2).path("value").asString()).isEqualTo("M-" + u);
        assertThat(ids.get(2).path("masked").asBoolean()).isFalse();

        assertThat(strings(d.path("masked_paths")))
                .containsExactlyInAnyOrder(
                        "attributes.birthday",
                        "attributes.contact_email",
                        "attributes.score",
                        "attributes.vip",
                        "attributes.spouse.phone",
                        "identifiers[0].value",
                        "identifiers[1].value");
    }

    @Test
    void detail_responseBodyNeverContainsRawMaskedValues() throws Exception {
        String id = ulid();
        String u = uniq();
        putOk(id, body(Map.of("birthday", "secret-bday-" + u), List.of(ident("EMAIL", "secret." + u + "@example.com"))));
        String raw = get("/api/v1/profiles/" + id).body();
        assertThat(raw).doesNotContain("secret-bday-" + u).doesNotContain("secret." + u);
    }

    @Test
    void detail_hasAllContractFields_andAsOfIsNow() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("name", "A"), null));
        clock.advance(Duration.ofMinutes(10));
        JsonNode d = getOk("/api/v1/profiles/" + id);
        for (String f : List.of("profile_id", "identifiers", "created_at", "updated_at", "attributes", "masked_paths", "as_of")) {
            assertThat(d.has(f)).as(f).isTrue();
        }
        assertThat(Instant.parse(d.path("as_of").asString())).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(Instant.parse(d.path("updated_at").asString())).isEqualTo(T0);
    }

    @Test
    void detail_unknownProfile_is404() throws Exception {
        assertProblem(get("/api/v1/profiles/" + ulid()), 404, "PROFILE_NOT_FOUND");
    }

    @Test
    void detail_readsLiveData_noCache() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("name", "first"), null));
        assertThat(getOk("/api/v1/profiles/" + id).path("attributes").path("name").asString()).isEqualTo("first");
        // 繞過 API 直接改表（日後整合入口的寫法）：下一次讀取即可見
        jdbc.update("update profile set attributes = '{\"name\":\"direct\"}'::jsonb where profile_id = ?", id);
        assertThat(getOk("/api/v1/profiles/" + id).path("attributes").path("name").asString()).isEqualTo("direct");
    }

    // ---------- /profiles/{id}/enrollments ----------

    @Test
    void profileEnrollments_newestFirst_statusFilter_paging() throws Exception {
        String profile = ulid();
        putOk(profile, Map.of());
        String j = publish(null, "客戶頁旅程-" + uniq(), null, "等待");
        String other = ulid();
        putOk(other, Map.of());
        String e1 = enrollment(j, 1, profile, "WAITING", T0.minusSeconds(300));
        String e2 = enrollment(j, 1, profile, "COMPLETED", T0.minusSeconds(200));
        String e3 = enrollment(j, 1, profile, "WAITING", T0.minusSeconds(100));
        enrollment(j, 1, other, "WAITING", T0.minusSeconds(50));

        JsonNode all = getOk("/api/v1/profiles/" + profile + "/enrollments");
        assertThat(field(all.path("items"), "enrollment_id")).containsExactly(e3, e2, e1);
        assertThat(all.path("next_cursor").isNull()).isTrue();
        JsonNode first = all.path("items").get(0);
        assertThat(first.path("profile_id").asString()).isEqualTo(profile);
        assertThat(first.path("journey_name").asString()).startsWith("客戶頁旅程-");
        assertThat(first.path("current_node_name").asString()).isEqualTo("等待");

        JsonNode waiting = getOk("/api/v1/profiles/" + profile + "/enrollments?status=WAITING");
        assertThat(field(waiting.path("items"), "enrollment_id")).containsExactly(e3, e1);
        JsonNode both = getOk("/api/v1/profiles/" + profile + "/enrollments?status=WAITING,COMPLETED");
        assertThat(both.path("items")).hasSize(3);

        JsonNode p1 = getOk("/api/v1/profiles/" + profile + "/enrollments?limit=2");
        assertThat(field(p1.path("items"), "enrollment_id")).containsExactly(e3, e2);
        String cursor = p1.path("next_cursor").asString();
        assertThat(cursor).isNotBlank();
        JsonNode p2 = getOk("/api/v1/profiles/" + profile + "/enrollments?limit=2&cursor=" + enc(cursor));
        assertThat(field(p2.path("items"), "enrollment_id")).containsExactly(e1);
        assertThat(p2.path("next_cursor").isNull()).isTrue();
    }

    @Test
    void profileEnrollments_existingProfileWithoutEnrollments_isEmptyList() throws Exception {
        String profile = ulid();
        putOk(profile, Map.of());
        JsonNode r = getOk("/api/v1/profiles/" + profile + "/enrollments");
        assertThat(r.path("items")).isEmpty();
        assertThat(r.path("next_cursor").isNull()).isTrue();
    }

    @Test
    void profileEnrollments_unknownProfile_is404_evenIfEnrollmentRowsExist() throws Exception {
        assertProblem(get("/api/v1/profiles/" + ulid() + "/enrollments"), 404, "PROFILE_NOT_FOUND");
    }

    @Test
    void profileEnrollments_badParams_are400() throws Exception {
        String profile = ulid();
        putOk(profile, Map.of());
        String base = "/api/v1/profiles/" + profile + "/enrollments";
        assertProblem(get(base + "?status=BOGUS"), 400, "VALIDATION_FAILED");
        assertProblem(get(base + "?limit=0"), 400, "VALIDATION_FAILED");
        assertProblem(get(base + "?limit=101"), 400, "VALIDATION_FAILED");
        assertProblem(get(base + "?cursor=garbage"), 400, "VALIDATION_FAILED");
    }
}
