package com.cdp.acceptance.journeybranchengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** SplitTestNode（STABLE 分組）、MergeNode、迴圈可重複經過（spec 03 SplitTestNode、journeys.md 第 4 節）。 */
class SplitMergeLoopAcceptanceTest extends BranchBase {

    private static Map<String, Object> variant(String port, int weight, boolean control) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("port", port);
        v.put("weight", weight);
        v.put("label", "組-" + port);
        if (control) v.put("is_control", true);
        return v;
    }

    private static Map<String, Object> split(String id, Map<String, Object> outs) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(
                "variants",
                List.of(variant("a", 45, false), variant("b", 45, false), variant("control", 10, true)));
        cfg.put("assignment_seed", "STABLE");
        return node(id, "SplitTestNode", cfg, outs);
    }

    /** spec 03：SHA-256(journey_id:node_id:profile_id) 前 8 位元組（無號）mod 100，依累積權重取第一個 bucket < 累積值者。 */
    private static String expectedPort(String journeyId, String nodeId, String profileId) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((journeyId + ":" + nodeId + ":" + profileId).getBytes(StandardCharsets.UTF_8));
        long bucket = new BigInteger(1, java.util.Arrays.copyOf(h, 8)).mod(BigInteger.valueOf(100)).longValue();
        if (bucket < 45) return "a";
        if (bucket < 90) return "b";
        return "control";
    }

    @Test
    void splitTest_assignsByStableHash_andRecordsVariable() throws Exception {
        String event = "evt_sp_" + uniq();
        String journey = publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(event, null, "split_1"),
                        split("split_1", Map.of("a", "end_a", "b", "end_b", "control", "end_c")),
                        end("end_a"),
                        end("end_b"),
                        end("end_c"))));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            String p = ulid();
            ingest(event, p, props());
            String e = enrollmentOf(p);
            String want = expectedPort(journey, "split_1", p);
            seen.add(want);
            assertThat(lastPort(e, "split_1")).as("profile " + p).isEqualTo(want);
            assertThat(visits(e, "end_" + (want.equals("control") ? "c" : want))).isEqualTo(1);
            assertThat(status(e)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject(
                            "select variables->'__split_split_1' is not null from enrollment_record where enrollment_id = ?",
                            Boolean.class,
                            e))
                    .as("分組結果寫入 variables.__split_split_1")
                    .isTrue();
        }
        assertThat(seen.size()).as("30 人應分到至少兩組").isGreaterThanOrEqualTo(2);
    }

    @Test
    void mergeNode_joinsBothConditionBranches() throws Exception {
        String event = "evt_mg_" + uniq();
        publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(event, null, "cond_1"),
                        cond("cond_1", leaf("trigger.properties.amount", "GT", 500), "merge_1", "merge_1"),
                        node("merge_1", "MergeNode", Map.of(), Map.of("next", "end_1")),
                        end("end_1"))));
        for (int amount : new int[] {600, 100}) {
            String p = ulid();
            ingest(event, p, props("amount", amount));
            String e = enrollmentOf(p);
            assertThat(visits(e, "merge_1")).isEqualTo(1);
            assertThat(lastPort(e, "merge_1")).isEqualTo("next");
            assertThat(visits(e, "end_1")).isEqualTo(1);
            assertThat(status(e)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void loop_canPassTheSameNodesRepeatedly_andKeepsSplitAssignment() throws Exception {
        String entry = "evt_lp_" + uniq();
        String stop = "evt_lp_stop_" + uniq();
        String journey = publish(def(
                Map.of(),
                List.of(
                        start(),
                        src(entry, null, "merge_1"),
                        node("merge_1", "MergeNode", Map.of(), Map.of("next", "split_1")),
                        split("split_1", Map.of("a", "wait_a", "b", "wait_b", "control", "wait_c")),
                        waitEvent("wait_a", stop, "PT1H", null, Map.of("occurred", "end_1", "timeout", "merge_1")),
                        waitEvent("wait_b", stop, "PT1H", null, Map.of("occurred", "end_1", "timeout", "merge_1")),
                        waitEvent("wait_c", stop, "PT1H", null, Map.of("occurred", "end_1", "timeout", "merge_1")),
                        end("end_1"))));
        String p = ulid();
        ingest(entry, p, props());
        String e = enrollmentOf(p);
        assertThat(status(e)).isEqualTo("WAITING");

        for (int lap = 0; lap < 3; lap++) {
            advance(Duration.ofHours(1).plusSeconds(1));
            assertThat(status(e)).as("第 " + (lap + 1) + " 圈後仍在迴圈").isEqualTo("WAITING");
        }
        assertThat(visits(e, "merge_1")).isEqualTo(4);
        assertThat(visits(e, "split_1")).isEqualTo(4);

        List<String> ports = jdbc.queryForList(
                "select out_port from node_trace where enrollment_id = ? and node_id = 'split_1' order by seq",
                String.class,
                e);
        assertThat(ports).hasSize(4).containsOnly(expectedPort(journey, "split_1", p));

        ingest(stop, p, props());
        assertThat(status(e)).isEqualTo("COMPLETED");
        assertThat(visits(e, "end_1")).isEqualTo(1);
        List<String> seqs = new ArrayList<>(jdbc.queryForList(
                "select seq::text from node_trace where enrollment_id = ? order by seq", String.class, e));
        assertThat(new HashSet<>(seqs)).as("seq 不重複").hasSameSizeAs(seqs);
    }
}
