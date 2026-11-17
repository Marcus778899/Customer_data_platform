package com.cdp.engine.node.builtin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.engine.node.condition.ConditionEvaluator;
import com.cdp.journey.JourneyDefinition;

/** 條件分流（journeys.md 第 4 節）：yes／no 兩個出口；原因句不引用屬性值，值放 evaluated。 */
@Component
public final class ConditionNode implements JourneyNode {

    @Override
    public String type() {
        return "ConditionNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        Object condition = config.values() == null ? null : config.values().get("condition");
        return new DataRequirements(ConditionEvaluator.profileFieldsOf(condition), Set.of(), false);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Object condition = context.config().values().get("condition");
        ConditionEvaluator.Evaluation result =
                ConditionEvaluator.evaluate(
                        condition,
                        ConditionEvaluator.progressResolver(
                                context.profileFields(),
                                context.trigger() == null ? Map.of() : context.trigger().properties()));
        return NodeResult.advance(
                result.matched() ? "yes" : "no",
                List.of(),
                "依節點 " + context.nodeId() + " 的條件判斷，結果為「" + (result.matched() ? "是" : "否") + "」。",
                result.evaluated());
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        if (config.get("condition", Map.class) == null) {
            return new ValidationResult(List.of("ConditionNode.config.condition 必填"));
        }
        return ValidationResult.OK;
    }
}
