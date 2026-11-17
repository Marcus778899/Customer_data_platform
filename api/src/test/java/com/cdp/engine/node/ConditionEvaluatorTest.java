package com.cdp.engine.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.engine.node.condition.ConditionEvaluator;

class ConditionEvaluatorTest {

    private static Map<String, Object> leaf(String field, String op, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "attribute");
        m.put("field", field);
        m.put("operator", op);
        m.put("value", value);
        return m;
    }

    private static boolean eval(Object cond, Map<String, Object> props) {
        return ConditionEvaluator.evaluate(cond, ConditionEvaluator.eventResolver(props)).matched();
    }

    @Test
    void numericComparisonsNeedNumbersOnBothSides() {
        assertThat(eval(leaf("properties.a", "GT", 500), Map.of("a", 600))).isTrue();
        assertThat(eval(leaf("properties.a", "GT", 500), Map.of("a", 500))).isFalse();
        assertThat(eval(leaf("properties.a", "GT", 500), Map.of("a", "600"))).isFalse();
        assertThat(eval(leaf("properties.a", "GTE", 500), Map.of("a", 500))).isTrue();
        assertThat(eval(leaf("properties.a", "LT", 5), Map.of("a", 1.5))).isTrue();
        assertThat(eval(leaf("properties.a", "LTE", 5), Map.of("a", true))).isFalse();
    }

    @Test
    void equalityIsJsonEquality() {
        assertThat(eval(leaf("properties.a", "EQ", 500), Map.of("a", 500L))).isTrue();
        assertThat(eval(leaf("properties.a", "EQ", 500), Map.of("a", "500"))).isFalse();
        assertThat(eval(leaf("properties.a", "EQ", List.of(1, 2)), Map.of("a", List.of(1L, 2L)))).isTrue();
        assertThat(eval(leaf("properties.a", "EQ", Map.of("k", 1)), Map.of("a", Map.of("k", 1)))).isTrue();
        assertThat(eval(leaf("properties.a", "EQ", List.of(1)), Map.of("a", List.of(1, 2)))).isFalse();
        assertThat(eval(leaf("properties.a", "NEQ", "x"), Map.of("a", "y"))).isTrue();
        assertThat(eval(leaf("properties.a", "NEQ", "x"), Map.of())).isFalse();
        assertThat(eval(leaf("properties.a", "IN", List.of("x", "y")), Map.of("a", "y"))).isTrue();
        assertThat(eval(leaf("properties.a", "IN", "y"), Map.of("a", "y"))).isFalse();
        assertThat(eval(leaf("properties.a", "NOT_IN", List.of("x")), Map.of("a", "y"))).isTrue();
        assertThat(eval(leaf("properties.a", "NOT_IN", List.of("x")), Map.of())).isFalse();
    }

    @Test
    void containsAndNullChecks() {
        assertThat(eval(leaf("properties.a", "CONTAINS", "or"), Map.of("a", "world"))).isTrue();
        assertThat(eval(leaf("properties.a", "CONTAINS", 1), Map.of("a", "world"))).isFalse();
        assertThat(eval(leaf("properties.a", "CONTAINS", "b"), Map.of("a", List.of("a", "b")))).isTrue();
        assertThat(eval(leaf("properties.a", "CONTAINS", "b"), Map.of("a", 5))).isFalse();
        assertThat(eval(leaf("properties.a", "IS_NULL", null), Map.of())).isTrue();
        assertThat(eval(leaf("properties.a", "IS_NOT_NULL", null), Map.of("a", 1))).isTrue();
        assertThat(eval(leaf("properties.a", "BOGUS", 1), Map.of("a", 1))).isFalse();
    }

    @Test
    void groupsAndMalformedInputNeverThrow() {
        Object and =
                Map.of("op", "AND", "children", List.of(leaf("properties.a", "EQ", 1), leaf("properties.b", "EQ", 2)));
        assertThat(eval(and, Map.of("a", 1, "b", 2))).isTrue();
        assertThat(eval(and, Map.of("a", 1, "b", 3))).isFalse();
        Object or =
                Map.of("op", "OR", "children", List.of(leaf("properties.a", "EQ", 1), leaf("properties.b", "EQ", 2)));
        assertThat(eval(or, Map.of("b", 2))).isTrue();
        Object not = Map.of("op", "NOT", "children", List.of(leaf("properties.a", "EQ", 1)));
        assertThat(eval(not, Map.of("a", 2))).isTrue();
        assertThat(eval(Map.of("op", "NOT", "children", List.of()), Map.of())).isFalse();
        assertThat(eval(Map.of("op", "XOR", "children", List.of(leaf("properties.a", "EQ", 1))), Map.of("a", 1)))
                .isFalse();
        assertThat(eval("garbage", Map.of())).isFalse();
        assertThat(eval(Map.of("foo", 1), Map.of())).isFalse();
    }

    @Test
    void resolversAndEvaluatedValues() {
        Object cond = leaf("trigger.properties.amount", "GT", 500);
        var r =
                ConditionEvaluator.evaluate(
                        cond, ConditionEvaluator.progressResolver(Map.of("tier", "G"), Map.of("amount", 700)));
        assertThat(r.matched()).isTrue();
        assertThat(r.evaluated()).containsEntry("trigger_properties_amount", 700);
        assertThat(ConditionEvaluator.progressResolver(Map.of("tier", "G"), null).apply("tier")).isEqualTo("G");
        assertThat(ConditionEvaluator.progressResolver(null, null).apply("trigger.properties.x")).isNull();
        assertThat(ConditionEvaluator.eventResolver(null).apply("properties.x")).isNull();
        assertThat(ConditionEvaluator.eventResolver(Map.of("x", 1)).apply("tier")).isNull();
        assertThat(ConditionEvaluator.eventMatches(null, Map.of())).isTrue();
        assertThat(ConditionEvaluator.profileFieldsOf(
                        Map.of("op", "AND", "children", List.of(cond, leaf("tier", "EQ", "G")))))
                .containsExactly("tier");
    }

    @Test
    void nonFiniteNumbersNeverThrow() {
        assertThat(eval(leaf("properties.a", "GT", 1), Map.of("a", Double.NaN))).isFalse();
        assertThat(eval(leaf("properties.a", "EQ", 1), Map.of("a", Double.POSITIVE_INFINITY))).isFalse();
    }
}
