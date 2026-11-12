package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 驗收條件 3：v1 有客戶等待中時核准 v2，v1 的客戶照 v1 走完；新事件進 v2。 */
class VersionBindingAcceptanceTest extends LifecycleBase {

    @Test
    void waitingCustomerOnV1_finishesOnV1_afterV2Approved() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("綁定-" + uniq(), ev, "PT1H"));

        String p1 = ingest(ev, ulid(), Map.of());
        String e1 = enrollmentOf(p1);
        assertThat(jdbc.queryForObject("select journey_version from enrollment_record where enrollment_id = ?", Integer.class, e1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from enrollment_record where enrollment_id = ?", String.class, e1))
                .isEqualTo("WAITING");

        // v2：沒有 SendMessage，等 PT1H 後直接結束
        int v = publishNext(editor, admin, id, plainDef("綁定-v2", ev, "PT1H"));
        assertThat(v).isEqualTo(2);

        // 等待中的客戶仍綁 v1
        assertThat(jdbc.queryForObject("select journey_version from enrollment_record where enrollment_id = ?", Integer.class, e1))
                .isEqualTo(1);

        // 之後的事件進 v2
        String p2 = ingest(ev, ulid(), Map.of());
        String e2 = enrollmentOf(p2);
        assertThat(jdbc.queryForObject("select journey_version from enrollment_record where enrollment_id = ?", Integer.class, e2))
                .isEqualTo(2);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        advanceLoop.runOnce();

        // v1 客戶照 v1 走：經過 send_1，有一筆 outbox（journey_version 1），完成
        assertThat(jdbc.queryForObject("select journey_version from enrollment_record where enrollment_id = ?", Integer.class, e1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from enrollment_record where enrollment_id = ?", String.class, e1))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from node_trace where enrollment_id = ? and node_id = 'send_1'", Integer.class, e1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from effect_outbox where enrollment_id = ? and journey_version = 1",
                        Integer.class,
                        e1))
                .isEqualTo(1);

        // v2 客戶走 v2：沒有 send_1、沒有 outbox
        assertThat(jdbc.queryForObject("select status from enrollment_record where enrollment_id = ?", String.class, e2))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from node_trace where enrollment_id = ? and node_id = 'send_1'", Integer.class, e2))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from effect_outbox where enrollment_id = ?", Integer.class, e2))
                .isZero();
    }

    @Test
    void draftAndPendingReview_doNotAffectLiveVersion() throws Exception {
        Actor editor = actor("EDITOR");
        Actor admin = actor("ADMIN");
        String ev = "evt_" + uniq();
        String id = publishNew(editor, admin, sendDef("上線不受影響-" + uniq(), ev, "PT1H"));
        int rev = save(editor, id, 0, plainDef("草稿版", ev, "PT9H"));
        submit(editor, id, rev);

        String p = ingest(ev, ulid(), Map.of());
        String e = enrollmentOf(p);
        assertThat(jdbc.queryForObject("select journey_version from enrollment_record where enrollment_id = ?", Integer.class, e))
                .as("尚未核准的草稿不會被綁定")
                .isEqualTo(1);
    }
}
