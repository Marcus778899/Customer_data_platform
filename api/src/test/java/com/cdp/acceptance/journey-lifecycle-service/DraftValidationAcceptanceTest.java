package com.cdp.acceptance.journeylifecycleservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** journeys.md §3 ValidationReport／Issue 與 §4 支援的節點與條件。 */
class DraftValidationAcceptanceTest extends LifecycleBase {

    private JsonNode validate(Map<String, Object> definition) throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "驗證-" + uniq());
        save(editor, id, 1, definition);
        return ok(as(editor, "POST", "/api/v1/journeys/" + id + "/draft/validate", null), 200);
    }

    private static JsonNode errorAt(JsonNode rep, String nodeId) {
        for (JsonNode e : rep.path("errors")) if (nodeId.equals(e.path("node_id").asString(null))) return e;
        return null;
    }

    private static Map<String, Object> cond(String field, String op, Object value) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("type", "attribute");
        leaf.put("field", field);
        leaf.put("operator", op);
        if (value != null) leaf.put("value", value);
        return leaf;
    }

    private static Map<String, Object> branchDef(Map<String, Object> condition) {
        return def(
                "分支",
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "cond_vip")),
                        node("cond_vip", "ConditionNode", Map.of("condition", condition), Map.of("yes", "end_1", "no", "end_2")),
                        node("end_1", "EndNode", Map.of(), Map.of()),
                        node("end_2", "EndNode", Map.of(), Map.of())));
    }

    @Test
    void fullFeatureDefinition_isValid() throws Exception {
        Map<String, Object> variantA = Map.of("port", "a", "weight", 50, "label", "A", "is_control", true);
        Map<String, Object> variantB = Map.of("port", "b", "weight", 50, "label", "B");
        Map<String, Object> d =
                def(
                        "全節點",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "cond_1")),
                                node(
                                        "cond_1",
                                        "ConditionNode",
                                        Map.of(
                                                "condition",
                                                Map.of(
                                                        "op",
                                                        "AND",
                                                        "children",
                                                        List.of(
                                                                cond("tier", "EQ", "vip"),
                                                                Map.of("op", "NOT", "children", List.of(cond("trigger.properties.coupon", "IS_NULL", null)))))),
                                        Map.of("yes", "split_1", "no", "wait_ev")),
                                node(
                                        "split_1",
                                        "SplitTestNode",
                                        Map.of("variants", List.of(variantA, variantB)),
                                        Map.of("a", "merge_1", "b", "merge_1")),
                                node(
                                        "wait_ev",
                                        "WaitForEventNode",
                                        Map.of("event_name", "evt_b", "timeout", "PT1H"),
                                        Map.of("occurred", "goal_1", "timeout", "merge_1")),
                                node("merge_1", "MergeNode", Map.of(), Map.of("next", "send_1")),
                                node(
                                        "send_1",
                                        "SendMessageNode",
                                        Map.of("template_id", "tpl.a-1", "channel_id", "ch_a"),
                                        Map.of("sent", "exit_1")),
                                node("exit_1", "ExitNode", Map.of("reason", "完成"), Map.of()),
                                node("goal_1", "GoalNode", Map.of("goal_id", "g1"), Map.of())));
        JsonNode rep = validate(d);
        assertThat(rep.path("errors").toString()).isEqualTo("[]");
        assertThat(rep.path("valid").asBoolean()).isTrue();
    }

    @Test
    void loopWithoutWait_isRule7Error_onFirstNodeInArray_listingAllNodes() throws Exception {
        Map<String, Object> d =
                def(
                        "迴圈",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "send_c")),
                                node("send_c", "SendMessageNode", Map.of("template_id", "t", "channel_id", "c"), Map.of("sent", "merge_d")),
                                node("merge_d", "MergeNode", Map.of(), Map.of("next", "send_c"))));
        JsonNode rep = validate(d);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        JsonNode loop = null;
        for (JsonNode e : rep.path("errors")) if (e.path("rule").asInt(-1) == 7) loop = e;
        assertThat(loop).as(rep.toString()).isNotNull();
        assertThat(loop.path("node_id").asString()).isEqualTo("send_c");
        assertThat(loop.path("message").asString()).contains("send_c").contains("merge_d");
        assertThat(loop.path("field").asString()).isEqualTo("definition.nodes[2]");
    }

    @Test
    void loopContainingWait_isAllowed() throws Exception {
        Map<String, Object> d =
                def(
                        "有等待的迴圈",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "send_c")),
                                node("send_c", "SendMessageNode", Map.of("template_id", "t", "channel_id", "c"), Map.of("sent", "wait_d")),
                                node("wait_d", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "send_c"))));
        JsonNode rep = validate(d);
        for (JsonNode e : rep.path("errors")) assertThat(e.path("rule").asInt(-1)).isNotEqualTo(7);
    }

    @Test
    void missingConditionOutput_pointsAtOutputField() throws Exception {
        Map<String, Object> d =
                def(
                        "缺出口",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "cond_vip")),
                                node("cond_vip", "ConditionNode", Map.of("condition", cond("tier", "EQ", "vip")), Map.of("yes", "end_1")),
                                node("end_1", "EndNode", Map.of(), Map.of())));
        JsonNode rep = validate(d);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        JsonNode e = errorAt(rep, "cond_vip");
        assertThat(e).as(rep.toString()).isNotNull();
        assertThat(e.path("field").asString()).isEqualTo("definition.nodes[2].outputs.no");
        assertThat(e.path("message").asString()).isNotBlank();
    }

    @Test
    void nodeIdMustBeSnakeCase_andUnique() throws Exception {
        Map<String, Object> d =
                def(
                        "壞 id",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "Src-1")),
                                node("Src-1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "end_1")),
                                node("end_1", "EndNode", Map.of(), Map.of()),
                                node("end_1", "EndNode", Map.of(), Map.of())));
        JsonNode rep = validate(d);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        List<String> fields = new ArrayList<>();
        for (JsonNode e : rep.path("errors")) fields.add(e.path("field").asString());
        assertThat(fields).anyMatch(f -> f.startsWith("definition.nodes[1]")).anyMatch(f -> f.startsWith("definition.nodes[3]"));
        for (JsonNode e : rep.path("errors")) assertThat(e.path("message").asString()).isNotBlank();
    }

    @Test
    void sendMessageIds_areTrimmedAndPatternChecked() throws Exception {
        Map<String, Object> bad = sendDef("訊息", "evt_a", "PT1H");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) bad.get("nodes");
        nodes.set(3, node("send_1", "SendMessageNode", Map.of("template_id", "有空 格", "channel_id", ""), Map.of("sent", "end_1")));
        JsonNode rep = validate(bad);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        JsonNode e = errorAt(rep, "send_1");
        assertThat(e).as(rep.toString()).isNotNull();
        assertThat(e.path("field").asString()).startsWith("definition.nodes[3]");

        Map<String, Object> padded = sendDef("訊息", "evt_a", "PT1H");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> n2 = (List<Map<String, Object>>) padded.get("nodes");
        n2.set(3, node("send_1", "SendMessageNode", Map.of("template_id", "  tpl_a  ", "channel_id", " ch_a "), Map.of("sent", "end_1")));
        assertThat(errorAt(validate(padded), "send_1")).as("去前後空白後合法").isNull();
    }

    @Test
    void conditionSubset_rejectsBehaviorAndSegmentLeaves_andDeepTrees() throws Exception {
        for (String type : List.of("behavior", "segment")) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("type", type);
            leaf.put("field", "x");
            leaf.put("operator", "EQ");
            leaf.put("value", 1);
            JsonNode rep = validate(branchDef(leaf));
            assertThat(rep.path("valid").asBoolean()).as(type).isFalse();
            assertThat(errorAt(rep, "cond_vip")).as(rep.toString()).isNotNull();
        }
        // NOT 恰一個子節點
        JsonNode not2 = validate(branchDef(Map.of("op", "NOT", "children", List.of(cond("a", "EQ", 1), cond("b", "EQ", 2)))));
        assertThat(errorAt(not2, "cond_vip")).isNotNull();
        // 深度 > 5
        Map<String, Object> deep = cond("a", "EQ", 1);
        for (int i = 0; i < 6; i++) deep = Map.of("op", "AND", "children", List.of(deep));
        assertThat(errorAt(validate(branchDef(deep)), "cond_vip")).as("深度超過 5").isNotNull();
        // 未知 operator
        assertThat(errorAt(validate(branchDef(cond("a", "LIKE", "x"))), "cond_vip")).isNotNull();
    }

    @Test
    void splitTest_weightsMustSumTo100() throws Exception {
        Map<String, Object> d =
                def(
                        "權重",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "split_1")),
                                node(
                                        "split_1",
                                        "SplitTestNode",
                                        Map.of(
                                                "variants",
                                                List.of(
                                                        Map.of("port", "a", "weight", 60, "label", "A"),
                                                        Map.of("port", "b", "weight", 60, "label", "B"))),
                                        Map.of("a", "end_1", "b", "end_1")),
                                node("end_1", "EndNode", Map.of(), Map.of())));
        JsonNode rep = validate(d);
        assertThat(rep.path("valid").asBoolean()).isFalse();
        assertThat(errorAt(rep, "split_1")).as(rep.toString()).isNotNull();
    }

    @Test
    void exitNodeNeedsReason_waitForEventNeedsTimeoutOutput() throws Exception {
        Map<String, Object> d =
                def(
                        "必填",
                        List.of(
                                node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                                node("src_1", "EventSourceNode", Map.of("event_name", "evt_a"), Map.of("next", "wait_ev")),
                                node(
                                        "wait_ev",
                                        "WaitForEventNode",
                                        Map.of("event_name", "evt_b", "timeout", "PT1H"),
                                        Map.of("occurred", "exit_1")),
                                node("exit_1", "ExitNode", Map.of("reason", ""), Map.of())));
        JsonNode rep = validate(d);
        assertThat(errorAt(rep, "exit_1")).as(rep.toString()).isNotNull();
        JsonNode w = errorAt(rep, "wait_ev");
        assertThat(w).as(rep.toString()).isNotNull();
        assertThat(w.path("field").asString()).isEqualTo("definition.nodes[2].outputs.timeout");
    }

    @Test
    void goalWarning_isReportedAsWarningNotError() throws Exception {
        JsonNode rep = validate(sendDef("警告", "evt_a", "PT1H"));
        assertThat(rep.path("errors").toString()).isEqualTo("[]");
        assertThat(rep.path("valid").asBoolean()).isTrue();
        boolean rule13 = false;
        for (JsonNode w : rep.path("warnings")) {
            if (w.path("rule").asInt(-1) == 13) {
                rule13 = true;
                assertThat(w.path("message").asString()).isNotBlank();
            }
        }
        assertThat(rule13).as("沒有 goal 也沒有目標節點 -> 規則 13 warning").isTrue();
    }

    @Test
    void submit_withErrors_isRejectedBefore_andApproveRevalidation_isNotNeededForValid() throws Exception {
        Actor editor = actor("EDITOR");
        String id = newDraft(editor, "送審驗證-" + uniq());
        Map<String, Object> d = sendDef("x", "evt_a", "PT1H");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) d.get("nodes");
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of()));
        int rev = save(editor, id, 1, d);
        var r = submitRaw(editor, id, rev, null);
        assertProblem(r, 400, "VALIDATION_FAILED");
        assertThat(errorAt(r.json(), "wait_1")).isNotNull();
        assertThat(reviews(id)).isEmpty();
        // 草稿未被鎖定
        assertThat(save(editor, id, rev, sendDef("ok", "evt_a", "PT1H"))).isEqualTo(rev + 1);
    }
}
