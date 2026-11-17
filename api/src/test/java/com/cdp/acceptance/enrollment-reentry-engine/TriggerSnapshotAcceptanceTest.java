package com.cdp.acceptance.enrollmentreentryengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 驗收條件 2：觸發快照依 spec 02 §5 截斷；profile_snapshot 只含被引用的欄位。
 * 直接讀 enrollment_record.trigger_snapshot（API 回應已遮罩，無法精確比對內容）。
 */
class TriggerSnapshotAcceptanceTest extends ReentryBase {

    private static final int LIMIT = 64 * 1024;

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.propertyNames().forEach(out::add);
        return new TreeSet<>(out).stream().toList();
    }

    private static List<Object> items(int count, int payloadChars) {
        List<Object> l = new ArrayList<>();
        String pad = "x".repeat(payloadChars);
        for (int i = 0; i < count; i++) l.add(Map.of("sku", "SKU-" + i, "note", pad));
        return l;
    }

    private int snapshotBytes(String enrollmentId) {
        return jdbc.queryForObject(
                "select octet_length(trigger_snapshot::text) from enrollment_record where enrollment_id = ?",
                Integer.class,
                enrollmentId);
    }

    // ---------- profile_snapshot ----------

    @Test
    void profileSnapshot_containsOnlyFieldsReferencedByConditionAndGlobalExit() throws Exception {
        String ev = "evt_ps_" + uniq();
        publish(def(
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(leaf("opt_out", "EQ", true)),
                                "on_match",
                                "EXITED")),
                List.of(
                        start(),
                        src(ev, null, "cond_1"),
                        cond("cond_1", leaf("tier", "EQ", "gold"), "wait_1", "wait_1"),
                        waitDelay("wait_1", "P1D", "end_1"),
                        end("end_1"))));
        String p = ulid();
        putProfile(
                p,
                props("tier", "gold", "opt_out", false, "city", "Taipei", "email", "a@example.com", "age", 33));
        ingest(ev, p, props("cart_id", "A"));
        JsonNode snap = snapshotOf(enrollmentOf(p)).path("profile_snapshot");
        assertThat(fieldNames(snap)).as("只有 Condition 與 global_exit 引用的欄位").containsExactly("opt_out", "tier");
        assertThat(snap.path("tier").asString()).isEqualTo("gold");
        assertThat(snap.path("opt_out").asBoolean(true)).isFalse();
    }

    @Test
    void profileSnapshot_isEmpty_whenNothingReferencesProfileAttributes() throws Exception {
        String ev = "evt_ps2_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(ev, null, "cond_1"),
                        // trigger.properties.* 是事件屬性，不是客戶屬性
                        cond("cond_1", leaf("trigger.properties.cart_id", "EQ", "A"), "end_1", "end_1"),
                        end("end_1"))));
        String p = ulid();
        putProfile(p, props("tier", "gold", "email", "a@example.com"));
        ingest(ev, p, props("cart_id", "A"));
        JsonNode snap = snapshotOf(enrollmentOf(p));
        assertThat(snap.has("profile_snapshot")).isTrue();
        assertThat(snap.path("profile_snapshot").size()).isZero();
    }

    @Test
    void profileSnapshot_referencedFieldMissingOnProfile_isNotInvented() throws Exception {
        String ev = "evt_ps3_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(ev, null, "cond_1"),
                        cond("cond_1", leaf("tier", "EQ", "gold"), "end_1", "end_1"),
                        end("end_1"))));
        String p = ulid();
        putProfile(p, props("city", "Taipei"));
        ingest(ev, p, props());
        JsonNode snap = snapshotOf(enrollmentOf(p)).path("profile_snapshot");
        assertThat(snap.has("city")).isFalse();
        assertThat(snap.path("tier").isMissingNode() || snap.path("tier").isNull()).isTrue();
    }

    // ---------- 截斷 ----------

    @Test
    void underLimit_snapshotKeptWhole_noTruncationMarkers() throws Exception {
        String ev = "evt_tr0_" + uniq();
        quickJourney(ev, Map.of());
        String p = ulid();
        ingest(ev, p, props("cart_id", "A", "items", items(30, 10)));
        JsonNode snap = snapshotOf(enrollmentOf(p));
        assertThat(snap.path("properties").path("items").size()).as("未超過 64 KB 不截斷").isEqualTo(30);
        assertThat(snap.path("properties").has("_truncated")).isFalse();
        assertThat(snap.path("event_id").asString()).isNotBlank();
        assertThat(snap.path("event_name").asString()).isEqualTo(ev);
    }

    @Test
    void overLimit_arraysKeepFirst20_withTruncatedFlagAndTotalCount() throws Exception {
        String ev = "evt_tr1_" + uniq();
        quickJourney(ev, Map.of());
        String p = ulid();
        // 100 筆 × 約 1 KB ≈ 100 KB > 64 KB；截成 20 筆後約 20 KB
        ingest(ev, p, props("cart_id", "A", "items", items(100, 1000)));
        String e = enrollmentOf(p);
        JsonNode props = snapshotOf(e).path("properties");
        assertThat(props.path("items").size()).as("陣列保留前 20 筆").isEqualTo(20);
        assertThat(props.path("items").get(0).path("sku").asString()).isEqualTo("SKU-0");
        assertThat(props.path("items").get(19).path("sku").asString()).isEqualTo("SKU-19");
        assertThat(props.path("_truncated").asBoolean(false)).isTrue();
        assertThat(props.path("_total_count").asInt()).isEqualTo(100);
        assertThat(props.path("cart_id").asString()).as("非陣列屬性不動").isEqualTo("A");
        assertThat(snapshotBytes(e)).isLessThanOrEqualTo(LIMIT);
    }

    @Test
    void overLimit_neverStoresSnapshotLargerThan64KB() throws Exception {
        String ev = "evt_tr2_" + uniq();
        quickJourney(ev, Map.of());
        String p = ulid();
        // 截成 20 筆後仍超過（每筆 5 KB）；後續步驟可保留白名單或拒絕建立，但絕不可存超過上限的快照
        ingest(ev, p, props("cart_id", "A", "items", items(100, 5000)));
        int n = enrollmentCount(p);
        assertThat(n).isLessThanOrEqualTo(1);
        if (n == 1) {
            assertThat(snapshotBytes(enrollmentOf(p))).isLessThanOrEqualTo(LIMIT);
        }
    }
}
