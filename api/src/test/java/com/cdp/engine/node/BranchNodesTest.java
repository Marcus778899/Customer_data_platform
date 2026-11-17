package com.cdp.engine.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
import com.cdp.engine.node.builtin.ConditionNode;
import com.cdp.engine.node.builtin.ExitNode;
import com.cdp.engine.node.builtin.GoalNode;
import com.cdp.engine.node.builtin.MergeNode;
import com.cdp.engine.node.builtin.SplitTestNode;
import com.cdp.engine.node.builtin.WaitForEventNode;

class BranchNodesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private static NodeContext ctx(Map<String, Object> config, WakeSignal signal, Map<String, Object> vars) {
        TriggerSnapshot t =
                new TriggerSnapshot("e1", "evt", NOW, "CUSTOMER_BEHAVIOR", Map.of("amount", 900), Map.of());
        return new NodeContext(
                "enr", "p1", "n1", new NodeConfig(config), Map.of(), Map.of(), Map.of(), List.of(), t, vars, NOW,
                signal, NOW, "j1");
    }

    @Test
    void waitForEvent_waitsThenOccurredOrTimeout() {
        WaitForEventNode node = new WaitForEventNode();
        Map<String, Object> cfg =
                Map.of("event_name", "paid", "timeout", "PT1H", "event_filter", Map.of("op", "AND"));
        NodeResult first = node.execute(ctx(cfg, null, Map.of()));
        assertThat(first.status()).isEqualTo(ResultStatus.WAIT);
        var wake = (WakeCondition.Event) first.wakeCondition();
        assertThat(wake.timeoutAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(wake.filter()).containsKey("op");
        assertThat(node.execute(ctx(cfg, new WakeSignal.EventArrived("paid", Map.of(), NOW), Map.of())).outPort())
                .isEqualTo("occurred");
        assertThat(node.execute(ctx(cfg, new WakeSignal.Timeout(NOW), Map.of())).outPort()).isEqualTo("timeout");
        assertThat(node.execute(ctx(cfg, new WakeSignal.TimerFired(NOW), Map.of())).outPort()).isEqualTo("timeout");
        NodeResult forever = node.execute(ctx(Map.of("event_name", "paid"), null, Map.of()));
        assertThat(((WakeCondition.Event) forever.wakeCondition()).timeoutAt()).isNull();
    }

    @Test
    void waitForEvent_validate() {
        WaitForEventNode node = new WaitForEventNode();
        assertThat(node.validate(new NodeConfig(Map.of("event_name", "x")), null).errors()).isEmpty();
        assertThat(node.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
        assertThat(node.validate(new NodeConfig(Map.of("event_name", "x", "timeout", "PT0S")), null).errors())
                .isNotEmpty();
        assertThat(node.validate(new NodeConfig(Map.of("event_name", "x", "timeout", "bad")), null).errors())
                .isNotEmpty();
        assertThat(node.requirements(new NodeConfig(Map.of()))).isEqualTo(DataRequirements.NONE);
        assertThat(node.type()).isEqualTo("WaitForEventNode");
    }

    @Test
    void condition_picksPortAndKeepsValuesOutOfReason() {
        ConditionNode node = new ConditionNode();
        Map<String, Object> cond =
                Map.of("type", "attribute", "field", "trigger.properties.amount", "operator", "GT", "value", 500);
        NodeResult r = node.execute(ctx(Map.of("condition", cond), null, Map.of()));
        assertThat(r.outPort()).isEqualTo("yes");
        assertThat(r.decisionReason()).doesNotContain("900");
        assertThat(r.evaluated()).containsEntry("trigger_properties_amount", 900);
        Map<String, Object> tierCond = Map.of("type", "attribute", "field", "tier", "operator", "EQ", "value", "G");
        assertThat(node.requirements(new NodeConfig(Map.of("condition", tierCond))).profileFields())
                .containsExactly("tier");
        assertThat(node.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
        assertThat(node.validate(new NodeConfig(Map.of("condition", cond)), null).errors()).isEmpty();
        assertThat(node.requirements(new NodeConfig(null)).profileFields()).isEmpty();
        assertThat(node.type()).isEqualTo("ConditionNode");
    }

    @Test
    void splitTest_isStableAndReusesStoredAssignment() {
        SplitTestNode node = new SplitTestNode();
        Map<String, Object> cfg =
                Map.of("variants", List.of(Map.of("port", "a", "weight", 50), Map.of("port", "b", "weight", 50)));
        NodeResult first = node.execute(ctx(cfg, null, Map.of()));
        NodeResult again = node.execute(ctx(cfg, null, Map.of()));
        assertThat(first.outPort()).isEqualTo(again.outPort()).isIn("a", "b");
        assertThat(first.variablePatch()).containsEntry("__split_n1", first.outPort());
        assertThat(node.execute(ctx(cfg, null, Map.of("__split_n1", "b"))).outPort()).isEqualTo("b");
        assertThat(node.validate(new NodeConfig(cfg), null).errors()).isEmpty();
        assertThat(node.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
        assertThat(node.requirements(new NodeConfig(cfg))).isEqualTo(DataRequirements.NONE);
        assertThat(node.type()).isEqualTo("SplitTestNode");
    }

    @Test
    void splitTest_weightsShortOfHundredFallBackToLastVariant() {
        SplitTestNode node = new SplitTestNode();
        Map<String, Object> cfg =
                Map.of("variants", List.of(Map.of("port", "a", "weight", 1), Map.of("port", "z", "weight", 1)));
        for (int i = 0; i < 20; i++) {
            NodeContext c =
                    new NodeContext(
                            "e", "p" + i, "n1", new NodeConfig(cfg), Map.of(), Map.of(), Map.of(), List.of(), null,
                            Map.of(), NOW, null, NOW, "j1");
            assertThat(node.execute(c).outPort()).isIn("a", "z");
        }
        assertThatThrownBy(() -> node.execute(ctx(Map.of(), null, Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mergeExitAndGoal() {
        MergeNode merge = new MergeNode();
        assertThat(merge.execute(ctx(Map.of(), null, Map.of())).outPort()).isEqualTo("next");
        ExitNode exit = new ExitNode();
        NodeResult e = exit.execute(ctx(Map.of("reason", "對照組"), null, Map.of()));
        assertThat(e.status()).isEqualTo(ResultStatus.EXIT);
        assertThat(e.terminationReason()).contains("對照組");
        assertThat(exit.execute(ctx(Map.of("reason", "x".repeat(300)), null, Map.of())).terminationReason())
                .hasSize(200);
        assertThat(exit.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
        assertThat(exit.validate(new NodeConfig(Map.of("reason", "r")), null).errors()).isEmpty();
        GoalNode goal = new GoalNode();
        assertThat(goal.execute(ctx(Map.of("goal_id", "g"), null, Map.of())).status()).isEqualTo(ResultStatus.GOAL);
        assertThat(goal.validate(new NodeConfig(Map.of()), null).errors()).isNotEmpty();
        assertThat(goal.validate(new NodeConfig(Map.of("goal_id", "g")), null).errors()).isEmpty();
        assertThat(merge.validate(null, null).errors()).isEmpty();
        assertThat(merge.requirements(null)).isEqualTo(DataRequirements.NONE);
        assertThat(exit.requirements(null)).isEqualTo(DataRequirements.NONE);
        assertThat(goal.requirements(null)).isEqualTo(DataRequirements.NONE);
        assertThat(List.of(merge.type(), exit.type(), goal.type())).containsExactly("MergeNode", "ExitNode", "GoalNode");
    }
}
