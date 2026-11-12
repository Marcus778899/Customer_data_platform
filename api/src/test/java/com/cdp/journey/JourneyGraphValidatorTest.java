package com.cdp.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JourneyGraphValidatorTest {

    private final JourneyGraphValidator validator = new JourneyGraphValidator();

    private static Map<String, Object> node(
            String id, String type, Map<String, Object> config, Map<String, Object> outs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("name", null);
        n.put("config", config);
        n.put("outputs", outs);
        return n;
    }

    private static Map<String, Object> def(List<Map<String, Object>> nodes) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "測試");
        d.put("description", null);
        d.put("settings", Map.of());
        d.put("entry_node", "start");
        d.put("nodes", new ArrayList<>(nodes));
        return d;
    }

    private static List<Map<String, Object>> basic() {
        return new ArrayList<>(
                List.of(
                        node("start", "StartNode", Map.of(), Map.of("next", "src_1")),
                        node("src_1", "EventSourceNode", Map.of("event_name", "evt"), Map.of("next", "wait_1")),
                        node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "end_1")),
                        node("end_1", "EndNode", Map.of(), Map.of())));
    }

    private static Map<String, Object> leaf(String field, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "attribute");
        m.put("field", field);
        m.put("operator", op);
        if (value != null) {
            m.put("value", value);
        }
        return m;
    }

    private static boolean hasRule(List<Issue> issues, int rule) {
        return issues.stream().anyMatch(i -> i.rule() != null && i.rule() == rule);
    }

    @Test
    void validBasicDefinitionHasOnlyGoalWarning() {
        ValidationReport r = validator.validate(def(basic()));
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
        assertThat(hasRule(r.warnings(), 13)).isTrue();
    }

    @Test
    void skeletonIsInvalid() {
        ValidationReport r = validator.validate(def(List.of(node("start", "StartNode", Map.of(), Map.of()))));
        assertThat(r.valid()).isFalse();
        assertThat(hasRule(r.errors(), 4)).isTrue();
    }

    @Test
    void structuralProblems() {
        Map<String, Object> d = def(basic());
        d.put("name", " ");
        d.put("description", 5);
        d.put("settings", "x");
        d.put("entry_node", "other");
        ValidationReport r = validator.validate(d);
        assertThat(r.errors())
                .extracting(Issue::field)
                .contains("definition.name", "definition.description", "definition.settings", "definition.entry_node");

        Map<String, Object> noNodes = new LinkedHashMap<>(d);
        noNodes.put("nodes", "x");
        assertThat(validator.validate(noNodes).errors()).extracting(Issue::field).contains("definition.nodes");
    }

    @Test
    void badNodeEntries() {
        List<Map<String, Object>> nodes = basic();
        List<Object> raw = new ArrayList<>(nodes);
        raw.add("not-an-object");
        raw.add(Map.of("type", "EndNode"));
        raw.add(node("x_1", "UnknownNode", Map.of(), Map.of()));
        Map<String, Object> d = def(nodes);
        d.put("nodes", raw);
        ValidationReport r = validator.validate(d);
        assertThat(r.errors())
                .extracting(Issue::field)
                .contains("definition.nodes[4]", "definition.nodes[5].id", "definition.nodes[6].type");
    }

    @Test
    void danglingOrphanAndSourceRules() {
        List<Map<String, Object>> nodes = basic();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", 5)));
        nodes.add(node("orphan", "EndNode", Map.of(), Map.of()));
        nodes.add(node("src_2", "EventSourceNode", Map.of("event_name", "e2"), Map.of("next", "ghost")));
        nodes.set(0, node("start", "StartNode", Map.of(), Map.of("next", List.of("src_1", "wait_1"))));
        ValidationReport r = validator.validate(def(nodes));
        assertThat(hasRule(r.errors(), 3)).isTrue();
        assertThat(hasRule(r.errors(), 5)).isTrue();
        assertThat(hasRule(r.errors(), 2)).isTrue();
        assertThat(hasRule(r.errors(), 14)).isTrue();
    }

    @Test
    void sourceAfterNonStartIsRule2() {
        List<Map<String, Object>> nodes = basic();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "src_1")));
        assertThat(hasRule(validator.validate(def(nodes)).errors(), 2)).isTrue();
    }

    @Test
    void loopWithoutWaitIsRule7_loopWithWaitIsNot() {
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        bad.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "a")));
        bad.add(node("a", "MergeNode", Map.of(), Map.of("next", "b")));
        bad.add(node("b", "MergeNode", Map.of(), Map.of("next", "a")));
        ValidationReport r = validator.validate(def(bad));
        Issue loop = r.errors().stream().filter(i -> i.rule() != null && i.rule() == 7).findFirst().orElseThrow();
        assertThat(loop.nodeId()).isEqualTo("a");
        assertThat(loop.field()).isEqualTo("definition.nodes[2]");
        assertThat(loop.message()).contains("a").contains("b");

        List<Map<String, Object>> self = new ArrayList<>(bad);
        self.set(2, node("a", "MergeNode", Map.of(), Map.of("next", "a")));
        assertThat(hasRule(validator.validate(def(self)).errors(), 7)).isTrue();

        List<Map<String, Object>> ok = new ArrayList<>(bad);
        ok.set(3, node("b", "WaitDelayNode", Map.of("duration", "PT1M"), Map.of("next", "a")));
        assertThat(hasRule(validator.validate(def(ok)).errors(), 7)).isFalse();
    }

    @Test
    void conditionNodeRules() {
        assertThat(conditionErrors(leaf("tier", "EQ", "vip"))).isEmpty();
        assertThat(conditionErrors(leaf("trigger.properties.coupon", "IS_NULL", null))).isEmpty();
        assertThat(conditionErrors(leaf("tier", "IN", List.of("a", "b")))).isEmpty();
        assertThat(conditionErrors(leaf("tier", "IN", List.of()))).isNotEmpty();
        assertThat(conditionErrors(leaf("tier", "EQ", null))).isNotEmpty();
        assertThat(conditionErrors(leaf("Bad Key", "EQ", 1))).isNotEmpty();
        assertThat(conditionErrors(leaf("tier", "LIKE", 1))).isNotEmpty();
        Map<String, Object> behavior = leaf("x", "EQ", 1);
        behavior.put("type", "behavior");
        assertThat(conditionErrors(behavior)).isNotEmpty();
        assertThat(conditionErrors(Map.of("op", "XOR", "children", List.of(leaf("a", "EQ", 1))))).isNotEmpty();
        assertThat(conditionErrors(Map.of("op", "AND", "children", List.of()))).isNotEmpty();
        assertThat(conditionErrors(Map.of("op", "NOT", "children", List.of(leaf("a", "EQ", 1), leaf("b", "EQ", 2)))))
                .isNotEmpty();
        Map<String, Object> deep = leaf("a", "EQ", 1);
        for (int i = 0; i < 6; i++) {
            deep = Map.of("op", "AND", "children", List.of(deep));
        }
        assertThat(conditionErrors(deep)).isNotEmpty();
        Map<String, Object> five = leaf("a", "EQ", 1);
        for (int i = 0; i < 5; i++) {
            five = Map.of("op", "AND", "children", List.of(five));
        }
        assertThat(conditionErrors(five)).isEmpty();
        assertThat(conditionErrors("text")).isNotEmpty();
    }

    private List<Issue> conditionErrors(Object condition) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        nodes.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "cond")));
        nodes.add(node("cond", "ConditionNode", Map.of("condition", condition), Map.of("yes", "e1", "no", "e1")));
        nodes.add(node("e1", "EndNode", Map.of(), Map.of()));
        return validator.validate(def(nodes)).errors();
    }

    @Test
    void conditionNodeWithoutConditionAndMissingPorts() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        nodes.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "cond")));
        nodes.add(node("cond", "ConditionNode", Map.of(), Map.of("yes", "e1", "maybe", "e1")));
        nodes.add(node("e1", "EndNode", Map.of(), Map.of()));
        ValidationReport r = validator.validate(def(nodes));
        assertThat(r.errors())
                .extracting(Issue::field)
                .contains(
                        "definition.nodes[2].config.condition",
                        "definition.nodes[2].outputs.no",
                        "definition.nodes[2].outputs.maybe");
    }

    @Test
    void eventFilterOnSourceAcceptsPropertiesFieldOnly() {
        List<Map<String, Object>> nodes = basic();
        nodes.set(
                1,
                node(
                        "src_1",
                        "EventSourceNode",
                        Map.of("event_name", "evt", "event_filter", leaf("properties.cart_total", "GT", 500)),
                        Map.of("next", "wait_1")));
        assertThat(validator.validate(def(nodes)).errors()).isEmpty();
        nodes.set(
                1,
                node(
                        "src_1",
                        "EventSourceNode",
                        Map.of("event_name", "evt", "event_filter", leaf("tier", "GT", 500)),
                        Map.of("next", "wait_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
        nodes.set(
                1,
                node(
                        "src_1",
                        "EventSourceNode",
                        Map.of("event_name", "evt", "profile_filter", Map.of("a", 1)),
                        Map.of("next", "wait_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
        nodes.set(1, node("src_1", "EventSourceNode", Map.of(), Map.of("next", "wait_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
    }

    @Test
    void splitTestRules() {
        Map<String, Object> a = Map.of("port", "a", "weight", 50, "label", "A", "is_control", true);
        Map<String, Object> b = Map.of("port", "b", "weight", 50, "label", "B");
        assertThat(splitErrors(List.of(a, b), Map.of("a", "e1", "b", "e1"))).isEmpty();
        assertThat(splitErrors(
                        List.of(a, Map.of("port", "b", "weight", 60, "label", "B")), Map.of("a", "e1", "b", "e1")))
                .isNotEmpty();
        assertThat(splitErrors(List.of(a), Map.of("a", "e1"))).isNotEmpty();
        assertThat(splitErrors(List.of(a, a), Map.of("a", "e1"))).isNotEmpty();
        assertThat(splitErrors(List.of(a, b), Map.of("a", "e1"))).isNotEmpty();
        Map<String, Object> bControl = Map.of("port", "b", "weight", 50, "label", "B", "is_control", true);
        assertThat(splitErrors(List.of(a, bControl), Map.of("a", "e1", "b", "e1"))).isNotEmpty();
        assertThat(splitErrors(List.of(a, Map.of("port", "Bad", "weight", 0, "label", "")), Map.of("a", "e1")))
                .isNotEmpty();
        assertThat(splitErrors(List.of(a, 5), Map.of("a", "e1"))).isNotEmpty();
    }

    private List<Issue> splitErrors(List<?> variants, Map<String, Object> outs) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        nodes.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "sp")));
        nodes.add(node("sp", "SplitTestNode", Map.of("variants", variants), outs));
        nodes.add(node("e1", "EndNode", Map.of(), Map.of()));
        return validator.validate(def(nodes)).errors();
    }

    @Test
    void splitSeedMustBeStable() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        nodes.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "sp")));
        nodes.add(
                node(
                        "sp",
                        "SplitTestNode",
                        Map.of(
                                "variants",
                                List.of(
                                        Map.of("port", "a", "weight", 50, "label", "A"),
                                        Map.of("port", "b", "weight", 50, "label", "B")),
                                "assignment_seed",
                                "RANDOM"),
                        Map.of("a", "e1", "b", "e1")));
        nodes.add(node("e1", "EndNode", Map.of(), Map.of()));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
    }

    @Test
    void waitForEventExitGoalRules() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "StartNode", Map.of(), Map.of("next", "src_1")));
        nodes.add(node("src_1", "EventSourceNode", Map.of("event_name", "e"), Map.of("next", "w")));
        nodes.add(
                node(
                        "w",
                        "WaitForEventNode",
                        Map.of("event_name", "x", "timeout", "bad"),
                        Map.of("occurred", "ex", "timeout", "g")));
        nodes.add(node("ex", "ExitNode", Map.of("reason", ""), Map.of()));
        nodes.add(node("g", "GoalNode", Map.of(), Map.of()));
        ValidationReport r = validator.validate(def(nodes));
        assertThat(r.errors())
                .extracting(Issue::field)
                .contains(
                        "definition.nodes[2].config.timeout",
                        "definition.nodes[3].config.reason",
                        "definition.nodes[4].config.goal_id");

        nodes.set(
                2,
                node(
                        "w",
                        "WaitForEventNode",
                        Map.of("event_name", "x", "timeout", "PT1H"),
                        Map.of("occurred", "ex", "timeout", "g")));
        nodes.set(3, node("ex", "ExitNode", Map.of("reason", "完成"), Map.of()));
        nodes.set(4, node("g", "GoalNode", Map.of("goal_id", "g1"), Map.of()));
        ValidationReport ok = validator.validate(def(nodes));
        assertThat(ok.errors()).as(ok.errors().toString()).isEmpty();
        assertThat(hasRule(ok.warnings(), 12)).isFalse();

        nodes.set(2, node("w", "WaitForEventNode", Map.of("event_name", "x"), Map.of("occurred", "ex")));
        nodes.remove(4);
        assertThat(hasRule(validator.validate(def(nodes)).warnings(), 12)).isTrue();
    }

    @Test
    void sendMessageIdsAndNormalize() {
        List<Map<String, Object>> nodes = basic();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "PT1H"), Map.of("next", "send_1")));
        nodes.add(
                node(
                        "send_1",
                        "SendMessageNode",
                        Map.of("template_id", "  tpl_a ", "channel_id", " ch:a "),
                        Map.of("sent", "end_1", "skipped", "end_1")));
        Map<String, Object> d = def(nodes);
        assertThat(validator.validate(d).errors()).isEmpty();

        Map<String, Object> normalized = validator.normalize(d);
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg =
                (Map<String, Object>) ((List<Map<String, Object>>) normalized.get("nodes")).get(4).get("config");
        assertThat(cfg).containsEntry("template_id", "tpl_a").containsEntry("channel_id", "ch:a");

        nodes.set(
                4,
                node(
                        "send_1",
                        "SendMessageNode",
                        Map.of("template_id", "a b", "channel_id", ""),
                        Map.of("sent", "end_1")));
        assertThat(validator.validate(def(nodes)).errors()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(validator.normalize(Map.of("name", "x")).get("nodes")).isNull();
    }

    @Test
    void settingsRules() {
        Map<String, Object> d = def(basic());
        d.put("settings", Map.of("reentry", Map.of("mode", "BOGUS")));
        assertThat(validator.validate(d).errors()).isNotEmpty();
        d.put("settings", Map.of("reentry", Map.of("mode", "DEDUP_KEY_WITH_COOLDOWN")));
        assertThat(validator.validate(d).errors()).hasSize(2);
        d.put(
                "settings",
                Map.of(
                        "reentry",
                        Map.of(
                                "mode",
                                "DEDUP_KEY_WITH_COOLDOWN",
                                "dedup_key_path",
                                "$.trigger.properties.cart_id",
                                "cooldown",
                                "P7D")));
        assertThat(validator.validate(d).errors()).isEmpty();
        d.put("settings", Map.of("reentry", "x"));
        assertThat(validator.validate(d).errors()).isNotEmpty();

        d.put(
                "settings",
                Map.of("goal", Map.of("condition", Map.of("type", "event", "event", "paid"), "window", "P7D")));
        ValidationReport withGoal = validator.validate(d);
        assertThat(withGoal.errors()).isEmpty();
        assertThat(hasRule(withGoal.warnings(), 13)).isFalse();
        d.put("settings", Map.of("goal", Map.of("condition", Map.of("type", "segment"))));
        assertThat(validator.validate(d).errors()).isNotEmpty();
        d.put("settings", Map.of("goal", "x"));
        assertThat(validator.validate(d).errors()).isNotEmpty();

        d.put(
                "settings",
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(
                                        Map.of("type", "event", "event", "paid", "since", "ENROLLMENT"),
                                        leaf("opt_out", "EQ", true)),
                                "on_match",
                                "EXITED")));
        assertThat(validator.validate(d).errors()).isEmpty();
        d.put(
                "settings",
                Map.of(
                        "global_exit",
                        Map.of(
                                "conditions",
                                List.of(Map.of("type", "event", "since", "ALWAYS"), Map.of("type", "segment"), "x"),
                                "on_match",
                                "NOPE")));
        assertThat(validator.validate(d).errors()).hasSizeGreaterThanOrEqualTo(4);
        d.put("settings", Map.of("global_exit", "x"));
        assertThat(validator.validate(d).errors()).isNotEmpty();
    }

    @Test
    void invalidDurations() {
        List<Map<String, Object>> nodes = basic();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "PT0S"), Map.of("next", "end_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of("duration", "abc"), Map.of("next", "end_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
        nodes.set(2, node("wait_1", "WaitDelayNode", Map.of(), Map.of("next", "end_1")));
        assertThat(validator.validate(def(nodes)).errors()).isNotEmpty();
    }

    @Test
    void duplicateAndMalformedIds() {
        List<Map<String, Object>> nodes = basic();
        nodes.add(node("end_1", "EndNode", Map.of(), Map.of()));
        nodes.add(node("Bad-Id", "EndNode", Map.of(), Map.of()));
        ValidationReport r = validator.validate(def(nodes));
        assertThat(r.errors()).extracting(Issue::field).contains("definition.nodes[4].id", "definition.nodes[5].id");
    }
}
