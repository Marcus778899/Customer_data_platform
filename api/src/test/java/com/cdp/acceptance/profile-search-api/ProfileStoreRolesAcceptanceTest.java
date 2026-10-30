package com.cdp.acceptance.profilesearchapi;

import static com.cdp.acceptance.security.AuthHttp.ADMIN_KEY;
import static com.cdp.acceptance.security.AuthHttp.INGEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp;
import com.cdp.acceptance.security.AuthHttp.Resp;

/**
 * profile-search-api 驗收：auth.md §2 角色矩陣中本單的端點，用真實資料（放行者要得 200，不只是「不是 401／403」）。
 * 讀取類（enrollments、profiles、journeys）：VIEWER／EDITOR／ADMIN session 與管理金鑰放行，攝取金鑰 403，無憑證 401；
 * {@code PUT /profiles/{id}}：只有攝取金鑰與管理金鑰，任何 session（含 ADMIN）403。
 */
class ProfileStoreRolesAcceptanceTest extends ProfileStoreBase {

    private Map<String, String> sessions;
    private String profile;
    private String journey;
    private List<String> readPaths;

    private void setup() throws Exception {
        sessions = new LinkedHashMap<>();
        for (String role : List.of("VIEWER", "EDITOR", "ADMIN")) sessions.put(role, AuthHttp.loginNewUser(port, role));
        profile = ulid();
        putOk(profile, Map.of());
        journey = publish(null, "角色-" + uniq(), null, "w");
        String e = enrollment(journey, 1, profile, "WAITING", T0);
        readPaths =
                List.of(
                        "/api/v1/profiles/search?q=" + enc(profile),
                        "/api/v1/profiles/" + profile,
                        "/api/v1/profiles/" + profile + "/enrollments",
                        "/api/v1/enrollments",
                        "/api/v1/enrollments?q=" + profile,
                        "/api/v1/enrollments/" + e,
                        "/api/v1/journeys",
                        "/api/v1/journeys/" + journey,
                        "/api/v1/journeys/" + journey + "/versions/1");
    }

    @Test
    void readEndpoints_allowViewerEditorAdminSessions_andAdminKey() throws Exception {
        setup();
        List<String> failures = new ArrayList<>();
        for (String path : readPaths) {
            for (Map.Entry<String, String> s : sessions.entrySet()) {
                Resp r = AuthHttp.session(port, "GET", path, s.getValue(), null);
                if (r.status() != 200) failures.add(s.getKey() + " GET " + path + " -> " + r.status());
            }
            Resp k = AuthHttp.bearer(port, "GET", path, ADMIN_KEY, null);
            if (k.status() != 200) failures.add("ADMIN_KEY GET " + path + " -> " + k.status());
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void readEndpoints_rejectIngestKey_with403_andNoCredentials_with401() throws Exception {
        setup();
        for (String path : readPaths) {
            Resp ingest = AuthHttp.bearer(port, "GET", path, INGEST_KEY, null);
            assertProblem(ingest, 403, "FORBIDDEN");
            Resp none = AuthHttp.bearer(port, "GET", path, null, null);
            assertProblem(none, 401, "UNAUTHENTICATED");
        }
    }

    @Test
    void ingestKeyOnUnknownResource_is403_not404() throws Exception {
        setup();
        assertProblem(AuthHttp.bearer(port, "GET", "/api/v1/profiles/" + ulid(), INGEST_KEY, null), 403, "FORBIDDEN");
        assertProblem(AuthHttp.session(port, "GET", "/api/v1/profiles/" + ulid(), sessions.get("VIEWER"), null), 404, "PROFILE_NOT_FOUND");
    }

    @Test
    void putProfile_allowsIngestKeyAndAdminKey_only() throws Exception {
        setup();
        String body = "{\"attributes\":{\"name\":\"x\"}}";
        assertThat(AuthHttp.bearer(port, "PUT", "/api/v1/profiles/" + ulid(), INGEST_KEY, body).status()).isEqualTo(200);
        assertThat(AuthHttp.bearer(port, "PUT", "/api/v1/profiles/" + ulid(), ADMIN_KEY, body).status()).isEqualTo(200);
        for (Map.Entry<String, String> s : sessions.entrySet()) {
            String id = ulid();
            Resp r = AuthHttp.session(port, "PUT", "/api/v1/profiles/" + id, s.getValue(), body);
            assertProblem(r, 403, "FORBIDDEN");
            assertProblem(get("/api/v1/profiles/" + id), 404, "PROFILE_NOT_FOUND");
        }
        assertProblem(AuthHttp.sendRaw(port, "PUT", "/api/v1/profiles/" + ulid(), null, body), 401, "UNAUTHENTICATED");
    }
}
