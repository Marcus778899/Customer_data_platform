package com.cdp.acceptance.journeybuilderintegration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** 驗收條件 3：v1 有客戶等待中時，經審核核准 v2；v1 的客戶照 v1 走完，新事件進 v2；不可自審。 */
class VersionCoexistenceAcceptanceTest extends BuilderBase {

    @Test
    void v1WaitingCustomer_finishesOnV1_whileV2TakesNewEntrants() throws Exception {
        String ev = "evt_v_" + uniq();
        Map<String, Object> v1 = def(
                Map.of(),
                List.of(
                        start(),
                        src(ev, null, "wait_1"),
                        waitDelay("wait_1", "PT1H", "send_1"),
                        send("send_1", Map.of("sent", "end_1")),
                        end("end_1")));
        String journey = publish(v1);

        String p1 = ulid();
        ingest(ev, p1, props());
        String e1 = enrollmentOf(p1);
        assertThat(versionOfEnrollment(e1)).isEqualTo(1);

        // v2：等 2 小時、沒有訊息；v1 客戶仍在等待時核准
        int v = publishNext(
                journey,
                def(
                        Map.of(),
                        List.of(start(), src(ev, null, "wait_1"), waitDelay("wait_1", "PT2H", "end_1"), end("end_1"))));
        assertThat(v).isEqualTo(2);
        assertThat(apiGet("/api/v1/journeys/" + journey).path("versions")).hasSize(2);
        assertThat(versionOfEnrollment(e1)).isEqualTo(1);

        String p2 = ulid();
        ingest(ev, p2, props());
        String e2 = enrollmentOf(p2);
        assertThat(versionOfEnrollment(e2)).isEqualTo(2);

        advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(status(e1)).isEqualTo("COMPLETED");
        assertThat(visits(e1, "send_1")).isEqualTo(1);
        assertThat(versionOfEnrollment(e1)).isEqualTo(1);
        assertThat(awaitMock(e1, 1)).isEqualTo(1);
        assertThat(status(e2)).isEqualTo("WAITING");

        advance(Duration.ofHours(1));
        assertThat(status(e2)).isEqualTo("COMPLETED");
        assertThat(visits(e2, "send_1")).isZero();
        assertThat(mockCount(e2)).isZero();

        // 各版本人數分開統計
        assertThat(statsNode(journey, 1, "src_1").path("entered").asInt()).isEqualTo(1);
        assertThat(statsNode(journey, 2, "src_1").path("entered").asInt()).isEqualTo(1);
    }

    @Test
    void submitterCannotApproveOwn_otherAdminCan_andDraftIsLockedWhilePending() throws Exception {
        String ev = "evt_sa_" + uniq();
        Actor adminA = actor("ADMIN");
        Actor adminB = actor("ADMIN");
        Map<String, Object> d = def(Map.of(), List.of(start(), src(ev, null, "end_1"), end("end_1")));
        String id = okJson(as(adminA, "POST", "/api/v1/journeys/drafts", Map.of("name", d.get("name"))), 201)
                .path("journey_id")
                .asString();
        int rev = okJson(
                        as(adminA, "PUT", "/api/v1/journeys/" + id + "/draft", Map.of("revision", 1, "definition", d)),
                        200)
                .path("revision")
                .asInt();
        String rid = okJson(as(adminA, "POST", "/api/v1/journeys/" + id + "/draft/submit", Map.of("revision", rev)), 201)
                .path("review_id")
                .asString();

        Resp self = as(adminA, "POST", "/api/v1/journeys/" + id + "/reviews/" + rid + "/approve", Map.of());
        assertThat(self.status()).as(self.body()).isEqualTo(403);
        assertThat(self.json().path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");

        Resp locked =
                as(adminA, "PUT", "/api/v1/journeys/" + id + "/draft", Map.of("revision", rev, "definition", d));
        assertThat(locked.status()).as("送審中草稿鎖定").isEqualTo(409);

        JsonNode ok =
                okJson(as(adminB, "POST", "/api/v1/journeys/" + id + "/reviews/" + rid + "/approve", Map.of()), 200);
        assertThat(ok.path("version").asInt()).isEqualTo(1);
        assertThat(apiGet("/api/v1/journeys/" + id).path("status").asString()).isEqualTo("ACTIVE");
    }
}
