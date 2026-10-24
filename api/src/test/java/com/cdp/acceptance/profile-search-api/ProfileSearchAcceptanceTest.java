package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** profile-search-api 驗收：profiles.md §3 {@code GET /profiles/search}（四種方式、完全比對、D-048）。 */
class ProfileSearchAcceptanceTest extends ProfileStoreBase {

    private JsonNode search(String q) throws Exception {
        return getOk("/api/v1/profiles/search?q=" + enc(q)).path("items");
    }

    @Test
    void searchByProfileId_isCaseInsensitive_matchedByProfileId() throws Exception {
        String id = ulid();
        putOk(id, Map.of());
        for (String q : List.of(id, id.toLowerCase(), id.toUpperCase())) {
            JsonNode items = search(q);
            assertThat(field(items, "profile_id")).as(q).containsExactly(id);
            assertThat(items.get(0).path("matched_by").asString()).isEqualTo("PROFILE_ID");
        }
    }

    @Test
    void searchByEmail_normalizesCaseAndSpaces_masksResult() throws Exception {
        String id = ulid();
        String u = uniq();
        putOk(id, body(Map.of("name", "Amy"), List.of(ident("EMAIL", "amy." + u + "@example.com"), ident("MEMBER_ID", "M-" + u))));
        JsonNode items = search("  Amy." + u + "@EXAMPLE.com ");
        assertThat(field(items, "profile_id")).containsExactly(id);
        JsonNode s = items.get(0);
        assertThat(s.path("matched_by").asString()).isEqualTo("EMAIL");
        assertThat(field(s.path("identifiers"), "type")).containsExactly("EMAIL", "MEMBER_ID");
        assertThat(s.path("identifiers").get(0).path("value").asString()).isEqualTo("a***@example.com");
        assertThat(s.path("identifiers").get(0).path("masked").asBoolean()).isTrue();
        assertThat(s.path("identifiers").get(1).path("value").asString()).isEqualTo("M-" + u);
        assertThat(s.has("created_at")).isTrue();
        assertThat(s.has("updated_at")).isTrue();
        assertThat(s.has("attributes")).as("ProfileSummary 不含屬性").isFalse();
    }

    @Test
    void searchByPhone_normalizesFormats() throws Exception {
        String id = ulid();
        String tail = String.valueOf(100_000_000 + (int) (Math.random() * 800_000_000));
        putOk(id, body(null, List.of(ident("PHONE", "+886" + tail))));
        for (String q : List.of("+886" + tail, "+886 " + tail.substring(0, 3) + "-" + tail.substring(3), "0" + tail)) {
            JsonNode items = search(q);
            assertThat(field(items, "profile_id")).as(q).containsExactly(id);
            assertThat(items.get(0).path("matched_by").asString()).isEqualTo("PHONE");
        }
    }

    @Test
    void searchByMemberLineDevice_isExact_caseSensitive_noPrefixNoFuzzy() throws Exception {
        String a = ulid();
        String b = ulid();
        String c = ulid();
        String u = uniq();
        putOk(a, body(null, List.of(ident("MEMBER_ID", "Mem-" + u))));
        putOk(b, body(null, List.of(ident("LINE_USER_ID", "Uline" + u))));
        putOk(c, body(null, List.of(ident("DEVICE_ID", "Dev-" + u))));

        assertThat(search("  Mem-" + u + " ").get(0).path("matched_by").asString()).isEqualTo("MEMBER_ID");
        assertThat(field(search("Mem-" + u), "profile_id")).containsExactly(a);
        assertThat(search("Uline" + u).get(0).path("matched_by").asString()).isEqualTo("LINE_USER_ID");
        assertThat(field(search("Dev-" + u), "profile_id")).containsExactly(c);

        assertThat(search("mem-" + u)).as("區分大小寫").isEmpty();
        assertThat(search("Mem-" + u.substring(0, 8))).as("不做前綴").isEmpty();
        assertThat(search("Mem-" + u + "x")).isEmpty();
        assertThat(search("em-" + u)).as("不做模糊").isEmpty();
    }

    @Test
    void search_noMatch_isEmptyList_not404() throws Exception {
        JsonNode body = getOk("/api/v1/profiles/search?q=" + enc("nobody." + uniq() + "@example.com"));
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items")).isEmpty();
        assertThat(search("zz-" + uniq())).isEmpty();
    }

    @Test
    void search_unionOfCandidates_sameCustomerListedOnce_firstMatchWins_profileIdFirst() throws Exception {
        String p = ulid();
        String other = ulid();
        putOk(p, body(null, List.of(ident("MEMBER_ID", p))));
        clock.advance(Duration.ofMinutes(1));
        putOk(other, body(null, List.of(ident("DEVICE_ID", p))));

        JsonNode items = search(p);
        assertThat(field(items, "profile_id")).containsExactly(p, other);
        assertThat(items.get(0).path("matched_by").asString()).as("同一客戶取最先命中的方式").isEqualTo("PROFILE_ID");
        assertThat(items.get(1).path("matched_by").asString()).isEqualTo("DEVICE_ID");
    }

    @Test
    void search_atSignAndOtherTypes_emailThenMemberIdUnion() throws Exception {
        // 含 @ 的字串同時可能是 EMAIL，也可能是 MEMBER_ID／DEVICE_ID 的原字串（規則 4 仍適用）
        String a = ulid();
        String b = ulid();
        String u = uniq();
        String v = "both." + u + "@example.com";
        putOk(a, body(null, List.of(ident("EMAIL", v))));
        putOk(b, body(null, List.of(ident("MEMBER_ID", v))));
        JsonNode items = search(v);
        assertThat(field(items, "profile_id")).containsExactlyInAnyOrder(a, b);
        for (JsonNode s : items) {
            assertThat(s.path("matched_by").asString())
                    .isEqualTo(s.path("profile_id").asString().equals(a) ? "EMAIL" : "MEMBER_ID");
        }
    }

    @Test
    void search_atMost20_items() throws Exception {
        assertThat(search(ulid()).size()).isLessThanOrEqualTo(20);
    }

    @Test
    void search_invalidQ_is400() throws Exception {
        assertProblem(get("/api/v1/profiles/search"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/profiles/search?q="), 400, "VALIDATION_FAILED");
        Resp tooLong = get("/api/v1/profiles/search?q=" + "x".repeat(201));
        assertProblem(tooLong, 400, "VALIDATION_FAILED");
        // 200 字剛好可以
        assertThat(get("/api/v1/profiles/search?q=" + "x".repeat(200)).status()).isEqualTo(200);
    }

    @Test
    void searchRouteIsNotShadowedByProfileIdPath() throws Exception {
        // /profiles/search 不可被當成 profile_id = "search"
        Resp r = get("/api/v1/profiles/search?q=" + enc(ulid()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().has("items")).isTrue();
    }
}
