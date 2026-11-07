package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.acceptance.security.AuthHttp.Resp;

import tools.jackson.databind.JsonNode;

/** journeys.md §3.5 停止、spec 05 §5 旅程層：立即生效（outbox CANCELLED、Mock 0 則）、Enrollment 背景轉 ABORTED 並寫軌跡。 */
class JourneyStopAcceptanceTest extends LifecycleBase {

    /** 跑一輪背景清理（JourneyStopSweeper#runOnce）；直到沒有進行中的該旅程 Enrollment 或最多 20 輪。 */
    private void sweepUntilDone(String journeyId) throws Exception {
        Object sweeper = ctx.getBean(Class.forName("com.cdp.engine.runtime.JourneyStopSweeper"));
        Method run = sweeper.getClass().getMethod("runOnce");
        for (int i = 0; i < 20; i++) {
            run.invoke(sweeper);
            Integer open =
                    jdbc.queryForObject(
                            "select count(*) from enrollment_record where journey_id = ?"
                                    + " and status in ('PENDING','ACTIVE','WAITING','SUSPENDED')",
                            Integer.class,
                            journeyId);
            if (open == 0) return;
        }
    }

    private Resp stop(Actor a, String id, Object body) throws Exception {
        return as(a, "POST", "/api/v1/journeys/" + id + "/stop", body);
    }

    @Test
    void stop_cancelsInFlightOutbox_sendsNothing_abortsEnrollmentsWithTrace() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendThenWaitDef("停止-" + uniq(), ev));

        // A：已到發送點（outbox PENDING），目前等在 wait_2
        String pa = ingest(ev, ulid(), Map.of());
        String ea = enrollmentOf(pa);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();
        String key =
                jdbc.queryForObject("select idempotency_key from effect_outbox where enrollment_id = ?", String.class, ea);
        assertThat(jdbc.queryForObject("select status from effect_outbox where idempotency_key = ?", String.class, key))
                .isEqualTo("PENDING");
        // B：剛進入，等在 wait_1
        String pb = ingest(ev, ulid(), Map.of());
        String eb = enrollmentOf(pb);
        int traceBefore =
                jdbc.queryForObject("select count(*) from node_trace where enrollment_id = ?", Integer.class, eb);

        Resp r = stop(editor, id, Map.of("reason", "活動取消"));
        JsonNode d = ok(r, 200);
        assertThat(d.path("status").asString()).isEqualTo("ABORTED");
        assertThat(d.path("stop_reason").asString()).isEqualTo("活動取消");
        assertThat(d.path("stopped_by").path("user_id").asString()).isEqualTo(editor.userId());
        assertThat(d.path("stopped_by").path("display_name").asString()).isEqualTo(editor.displayName());
        assertThat(instant(d.path("stopped_at"))).isEqualTo(clock.instant());

        // 立即生效（還沒跑背景清理）：outbox 已 CANCELLED
        assertThat(jdbc.queryForObject("select status from effect_outbox where idempotency_key = ?", String.class, key))
                .isEqualTo("CANCELLED");

        // relay 不推、Mock 通道 0 則
        relay.runOnce();
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("select count(*) from mock_channel_message where idempotency_key = ?", Integer.class, key))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from mock_channel_message where enrollment_id in (?, ?)", Integer.class, ea, eb))
                .isZero();

        // 背景清理後 Enrollment 轉 ABORTED、寫軌跡、帶停止原因
        sweepUntilDone(id);
        for (String e : new String[] {ea, eb}) {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "select status, termination_reason, terminated_at from enrollment_record where enrollment_id = ?", e);
            assertThat(row.get("status")).as(e).isEqualTo("ABORTED");
            assertThat((String) row.get("termination_reason")).contains("活動取消");
            assertThat(row.get("terminated_at")).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from node_trace where enrollment_id = ?", Integer.class, eb))
                .as("停止要寫軌跡").isGreaterThan(traceBefore);

        // 已停止後新事件不再進入
        String p3 = ingest(ev, ulid(), Map.of());
        assertThat(jdbc.queryForObject("select count(*) from enrollment_record where profile_id = ?", Integer.class, p3))
                .isZero();
        // 時間再走，也不會有任何訊息送出
        clock.advance(Duration.ofDays(2));
        advanceLoop.runOnce();
        relay.runOnce();
        Thread.sleep(1000);
        assertThat(jdbc.queryForObject("select count(*) from mock_channel_message where enrollment_id in (?, ?)", Integer.class, ea, eb))
                .isZero();
    }

    @Test
    void stop_rules_idempotent_roles_andAbortedJourneyIsFrozen() throws Exception {
        Actor editor = actor("EDITOR");
        Actor viewer = actor("VIEWER");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin, sendDef("凍結-" + uniq(), "evt_" + uniq(), "PT1H"));

        assertProblem(stop(viewer, id, Map.of("reason", "x")), 403, "FORBIDDEN");
        assertThat(stop(editor, id, Map.of()).status()).isEqualTo(400);
        assertThat(stop(editor, id, Map.of("reason", "")).status()).isEqualTo(400);
        assertThat(stop(editor, id, Map.of("reason", "x".repeat(201))).status()).isEqualTo(400);
        assertProblem(stop(editor, ulid(), Map.of("reason", "x")), 404, "JOURNEY_NOT_FOUND");
        assertThat(getDetail(id).path("status").asString()).isEqualTo("ACTIVE");

        ok(stop(editor, id, Map.of("reason", "第一次")), 200);
        // 再停 -> 200，不改原因與停止者
        JsonNode again = ok(stop(admin, id, Map.of("reason", "第二次")), 200);
        assertThat(again.path("status").asString()).isEqualTo("ABORTED");
        assertThat(again.path("stop_reason").asString()).isEqualTo("第一次");
        assertThat(again.path("stopped_by").path("user_id").asString()).isEqualTo(editor.userId());

        // 草稿、建立版本都被擋
        assertProblem(saveRaw(editor, id, 0, sendDef("x", "e", "PT1H")), 409, "JOURNEY_ABORTED");
        assertProblem(
                key("POST", "/api/v1/journeys", withJourneyId(sendDef("管理金鑰", "evt_" + uniq(), "PT1H"), id)),
                409,
                "JOURNEY_ABORTED");
        assertThat(getDetail(id).path("versions")).hasSize(1);
        JsonNode summary = ok(key("GET", "/api/v1/journeys?status=ABORTED&limit=100", null), 200).path("items");
        assertThat(field(summary, "journey_id")).contains(id);
    }

    @Test
    void stop_draftJourney_isNotPublished() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "未發佈-" + uniq());
        assertProblem(stop(editor, id, Map.of("reason", "x")), 409, "JOURNEY_NOT_PUBLISHED");
        assertThat(getDetail(id).path("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void stop_withdrawsPendingReview() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        Actor stopper = actor("EDITOR");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("停止撤回-" + uniq(), ev, "PT1H"));
        int rev = save(editor, id, 0, sendDef("下一版", ev, "PT2H"));
        String rid = submit(editor, id, rev);

        ok(stop(stopper, id, Map.of("reason", "不做了")), 200);
        JsonNode rv = reviews(id).get(0);
        assertThat(rv.path("review_id").asString()).isEqualTo(rid);
        assertThat(rv.path("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(rv.path("reason").asString()).isEqualTo("旅程已停止");
        assertThat(rv.path("decided_by").path("user_id").asString()).isEqualTo(stopper.userId());
        assertThat(rv.path("decided_at").asString()).isNotBlank();
        assertProblem(reviewAction(admin, id, rid, "approve", Map.of()), 409, "REVIEW_NOT_PENDING");
    }

    @Test
    void stop_byAdminKey_hasNullStoppedBy() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String id = publishNew(editor, admin, sendDef("金鑰停止-" + uniq(), "evt_" + uniq(), "PT1H"));
        JsonNode d = ok(key("POST", "/api/v1/journeys/" + id + "/stop", Map.of("reason", "維運")), 200);
        assertThat(d.path("status").asString()).isEqualTo("ABORTED");
        assertThat(d.path("stopped_by").isNull()).isTrue();
        assertThat(d.path("stop_reason").asString()).isEqualTo("維運");
    }

    private static Map<String, Object> withJourneyId(Map<String, Object> def, String id) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("journey_id", id);
        m.putAll(def);
        return m;
    }
}
