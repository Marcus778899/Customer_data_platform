package com.cdp.acceptance.profilesearchapi;

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

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：profiles.md §1.1 正規化、§2 {@code PUT /profiles/{id}}。 */
class ProfileWriteAcceptanceTest extends ProfileStoreBase {

    private String storedIdentifier(String type, String value) {
        List<String> rows =
                jdbc.queryForList(
                        "select id_value from profile_identifier where id_type = ? and id_value = ?",
                        String.class,
                        type,
                        value);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String rawAttributes(String profileId) {
        return jdbc.queryForObject("select attributes::text from profile where profile_id = ?", String.class, profileId);
    }

    @Test
    void put_createsProfile_returns200_withMaskedDetailAndSortedIdentifiers() throws Exception {
        String id = ulid();
        String u = uniq();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", "Amy");
        attrs.put("tier", 3);
        attrs.put("birthday", "1990-01-01");
        JsonNode d =
                putOk(
                        id,
                        body(
                                attrs,
                                List.of(ident("MEMBER_ID", "M-" + u), ident("EMAIL", "  Amy." + u + "@Example.com "))));

        assertThat(d.path("profile_id").asString()).isEqualTo(id);
        assertThat(Instant.parse(d.path("created_at").asString())).isEqualTo(T0);
        assertThat(Instant.parse(d.path("updated_at").asString())).isEqualTo(T0);
        assertThat(Instant.parse(d.path("as_of").asString())).isEqualTo(T0);
        assertThat(d.has("matched_by")).isFalse();

        // 依 type、value 排序：EMAIL 在前、MEMBER_ID 在後
        JsonNode ids = d.path("identifiers");
        assertThat(field(ids, "type")).containsExactly("EMAIL", "MEMBER_ID");
        assertThat(ids.get(0).path("value").asString()).isEqualTo("a***@example.com");
        assertThat(ids.get(0).path("masked").asBoolean()).isTrue();
        assertThat(ids.get(1).path("value").asString()).isEqualTo("M-" + u);
        assertThat(ids.get(1).path("masked").asBoolean()).isFalse();

        assertThat(d.path("attributes").path("name").asString()).isEqualTo("Amy");
        assertThat(d.path("attributes").path("tier").asInt()).isEqualTo(3);
        assertThat(d.path("attributes").path("birthday").asString()).isEqualTo("***");
        assertThat(strings(d.path("masked_paths"))).containsExactlyInAnyOrder("attributes.birthday", "identifiers[0].value");
    }

    @Test
    void put_emptyBody_createsBlankProfile() throws Exception {
        String id = ulid();
        JsonNode d = putOk(id, Map.of());
        assertThat(d.path("attributes").isObject()).isTrue();
        assertThat(d.path("attributes").size()).isZero();
        assertThat(d.path("identifiers")).isEmpty();
        assertThat(d.path("masked_paths")).isEmpty();
        assertThat(getOk("/api/v1/profiles/" + id).path("profile_id").asString()).isEqualTo(id);
    }

    @Test
    void put_attributes_shallowMerge_overwriteDeleteKeep() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("name", "A", "tier", 1, "note", "n1"), null));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("tier", 2);
        second.put("note", null);
        second.put("city", "Taipei");
        JsonNode d = putOk(id, body(second, null));

        assertThat(d.path("attributes").path("name").asString()).isEqualTo("A");
        assertThat(d.path("attributes").path("tier").asInt()).isEqualTo(2);
        assertThat(d.path("attributes").path("city").asString()).isEqualTo("Taipei");
        assertThat(d.path("attributes").has("note")).isFalse();
        assertThat(JSON.readTree(rawAttributes(id)).has("note")).isFalse();
    }

    @Test
    void put_attributes_nestedObjectIsReplacedNotDeepMerged() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("city", Map.of("x", 1)), null));
        JsonNode d = putOk(id, body(Map.of("city", Map.of("y", 2)), null));
        assertThat(d.path("attributes").path("city").has("x")).isFalse();
        assertThat(d.path("attributes").path("city").path("y").asInt()).isEqualTo(2);
    }

    @Test
    void put_omittedAttributesAndIdentifiers_keepExisting() throws Exception {
        String id = ulid();
        String u = uniq();
        putOk(id, body(Map.of("name", "Keep"), List.of(ident("MEMBER_ID", "K-" + u))));
        JsonNode d = putOk(id, Map.of());
        assertThat(d.path("attributes").path("name").asString()).isEqualTo("Keep");
        assertThat(d.path("identifiers")).hasSize(1);
    }

    @Test
    void put_identifiers_areAddOnly_andIdempotent() throws Exception {
        String id = ulid();
        String u = uniq();
        putOk(id, body(null, List.of(ident("EMAIL", "a" + u + "@example.com"))));
        putOk(id, body(null, List.of(ident("MEMBER_ID", "M-" + u))));
        JsonNode again = putOk(id, body(null, List.of(ident("EMAIL", "A" + u + "@Example.com"), ident("MEMBER_ID", "M-" + u))));
        assertThat(field(again.path("identifiers"), "type")).containsExactly("EMAIL", "MEMBER_ID");
        Integer n =
                jdbc.queryForObject("select count(*) from profile_identifier where profile_id = ?", Integer.class, id);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void put_existingProfile_advancesUpdatedAt_keepsCreatedAt() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("name", "A"), null));
        clock.advance(Duration.ofMinutes(5));
        JsonNode d = putOk(id, body(Map.of("name", "B"), null));
        assertThat(Instant.parse(d.path("created_at").asString())).isEqualTo(T0);
        assertThat(Instant.parse(d.path("updated_at").asString())).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    @Test
    void put_normalizesIdentifiers_beforeStoring() throws Exception {
        String id = ulid();
        String u = uniq();
        String digits = String.valueOf(Math.abs(u.hashCode()) % 90_000_000 + 10_000_000); // 8 位數
        putOk(
                id,
                body(
                        null,
                        List.of(
                                ident("EMAIL", " Mixed." + u + "@EXAMPLE.com "),
                                ident("PHONE", " +886 (9" + digits.substring(0, 2) + ")-" + digits.substring(2, 5) + "." + digits.substring(5) + " "),
                                ident("MEMBER_ID", "  m-" + u.toUpperCase() + "  "),
                                ident("DEVICE_ID", " dev-" + u + " "),
                                ident("LINE_USER_ID", " U" + u + " "))));
        assertThat(storedIdentifier("EMAIL", "mixed." + u + "@example.com")).isNotNull();
        assertThat(storedIdentifier("PHONE", "+8869" + digits)).isNotNull();
        assertThat(storedIdentifier("MEMBER_ID", "m-" + u.toUpperCase())).isNotNull();
        assertThat(storedIdentifier("DEVICE_ID", "dev-" + u)).isNotNull();
        assertThat(storedIdentifier("LINE_USER_ID", "U" + u)).isNotNull();
    }

    @Test
    void put_phoneWithLeadingZero_getsDefaultCountryCode() throws Exception {
        // CDP_PHONE_DEFAULT_COUNTRY_CODE=886（測試以候選屬性名覆寫）
        String id = ulid();
        String tail = String.valueOf(100_000_000 + (int) (Math.random() * 800_000_000));
        putOk(id, body(null, List.of(ident("PHONE", "0" + tail.substring(0, 3) + "-" + tail.substring(3)))));
        assertThat(storedIdentifier("PHONE", "+886" + tail)).isNotNull();
    }

    @Test
    void put_normalizedIdentifier_sameAcrossFormats_isSameCustomer() throws Exception {
        String a = ulid();
        String b = ulid();
        String u = uniq();
        putOk(a, body(null, List.of(ident("EMAIL", "dup." + u + "@example.com"))));
        Resp r = putProfile(b, body(null, List.of(ident("EMAIL", " DUP." + u + "@EXAMPLE.COM"))));
        assertProblem(r, 409, "IDENTIFIER_CONFLICT");
    }

    // ---------- 驗證 ----------

    private void assertValidationField(Resp r, String field) {
        assertProblem(r, 400, "VALIDATION_FAILED");
        assertThat(field(r.json().path("errors"), "field")).as(r.body()).contains(field);
    }

    @Test
    void put_invalidIdentifiers_are400_withIndexedFieldPath_andNothingWritten() throws Exception {
        String u = uniq();
        List<Map<String, Object>> badOnes =
                List.of(
                        ident("EMAIL", "not-an-email"),
                        ident("EMAIL", "a b@example.com"),
                        ident("PHONE", "abc"),
                        ident("PHONE", "+0123456789"),
                        ident("PHONE", "+123"),
                        ident("MEMBER_ID", "   "),
                        ident("MEMBER_ID", "x".repeat(201)),
                        ident("FAX", "12345"));
        for (Map<String, Object> bad : badOnes) {
            String id = ulid();
            Resp r =
                    putProfile(
                            id,
                            body(Map.of("name", "ShouldNotExist"), List.of(ident("MEMBER_ID", "ok-" + u + id), bad)));
            if (bad.get("type").equals("FAX")) {
                assertProblem(r, 400, "VALIDATION_FAILED");
            } else {
                assertValidationField(r, "identifiers[1].value");
            }
            assertProblem(get("/api/v1/profiles/" + id), 404, "PROFILE_NOT_FOUND");
            assertThat(storedIdentifier("MEMBER_ID", "ok-" + u + id)).as("整筆不寫入").isNull();
        }
    }

    @Test
    void put_invalidAttributes_are400() throws Exception {
        assertValidationField(putProfile(ulid(), body(Map.of("Name", "x"), null)), "attributes.Name");
        assertProblem(putProfile(ulid(), body(Map.of("1abc", "x"), null)), 400, "VALIDATION_FAILED");
        assertProblem(putProfile(ulid(), body(Map.of("has-dash", "x"), null)), 400, "VALIDATION_FAILED");
        assertProblem(putProfile(ulid(), "{\"attributes\":[1,2]}"), 400, "VALIDATION_FAILED");
        assertProblem(putProfile(ulid(), "{\"identifiers\":{}}"), 400, "VALIDATION_FAILED");
        assertProblem(putProfile(ulid(), "not json"), 400, "VALIDATION_FAILED");
    }

    @Test
    void put_attributesOver32KbAfterMerge_is400_andUnchanged() throws Exception {
        String id = ulid();
        putOk(id, body(Map.of("note", "x".repeat(20_000)), null));
        Resp r = putProfile(id, body(Map.of("city", "y".repeat(20_000)), null));
        assertProblem(r, 400, "VALIDATION_FAILED");
        JsonNode raw = JSON.readTree(rawAttributes(id));
        assertThat(raw.has("city")).isFalse();
        assertThat(raw.path("note").asString()).hasSize(20_000);
    }

    @Test
    void put_nonUlidProfileId_is400() throws Exception {
        Resp r = putProfile("not-a-ulid", Map.of());
        assertProblem(r, 400, "VALIDATION_FAILED");
    }

    // ---------- 衝突 ----------

    @Test
    void put_identifierBelongingToAnotherCustomer_is409_wholeRequestRolledBack_noLeak() throws Exception {
        String a = ulid();
        String b = ulid();
        String u = uniq();
        putOk(a, body(Map.of("name", "A"), List.of(ident("EMAIL", "own." + u + "@example.com"))));

        // b 先存在，之後的 PUT 衝突：屬性與其他識別碼都不可寫入
        putOk(b, body(Map.of("name", "B-before"), null));
        Resp r =
                putProfile(
                        b,
                        body(
                                Map.of("name", "B-after"),
                                List.of(ident("MEMBER_ID", "new-" + u), ident("EMAIL", "OWN." + u + "@example.com"))));
        assertProblem(r, 409, "IDENTIFIER_CONFLICT");
        assertThat(r.body()).as("不揭露對方 ID").doesNotContain(a);
        JsonNode d = getOk("/api/v1/profiles/" + b);
        assertThat(d.path("attributes").path("name").asString()).isEqualTo("B-before");
        assertThat(d.path("identifiers")).isEmpty();
        assertThat(storedIdentifier("MEMBER_ID", "new-" + u)).isNull();

        // 新客戶衝突：不建立
        String c = ulid();
        assertProblem(
                putProfile(c, body(null, List.of(ident("EMAIL", "own." + u + "@example.com")))),
                409,
                "IDENTIFIER_CONFLICT");
        assertProblem(get("/api/v1/profiles/" + c), 404, "PROFILE_NOT_FOUND");
    }

    @Test
    void put_sameValueDifferentType_isNotAConflict() throws Exception {
        String a = ulid();
        String b = ulid();
        String u = uniq();
        String v = "same." + u + "@example.com";
        putOk(a, body(null, List.of(ident("EMAIL", v))));
        putOk(b, body(null, List.of(ident("MEMBER_ID", v))));
    }

    @Test
    void put_concurrentClaimOfSameIdentifier_exactlyOneWins() throws Exception {
        String u = uniq();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = ulid();
                Callable<Resp> c =
                        () -> {
                            go.await();
                            return putProfile(id, body(null, List.of(ident("EMAIL", "race." + u + "@example.com"))));
                        };
                fs.add(pool.submit(c));
            }
            go.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Resp> f : fs) {
                Resp r = f.get();
                if (r.status() == 200) ok++;
                else if (r.status() == 409) conflict++;
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void put_withoutCredentials_is401() throws Exception {
        Resp r = AuthHttp.sendRaw(port, "PUT", "/api/v1/profiles/" + ulid(), null, "{}");
        assertProblem(r, 401, "UNAUTHENTICATED");
    }
}
