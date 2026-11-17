package com.cdp.engine.node.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/** 達標終點（03 §5）：Enrollment 轉 GOAL_MET。 */
@Component
public final class GoalNode implements JourneyNode {

    @Override
    public String type() {
        return "GoalNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("goal_id", context.config().get("goal_id", String.class));
        String text = "旅程走到目標節點 " + context.nodeId() + "，已達成目標。";
        return NodeResult.goal(text, text, evaluated);
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        String goalId = config.get("goal_id", String.class);
        if (goalId == null || goalId.isBlank()) {
            return new ValidationResult(List.of("GoalNode.config.goal_id 必填"));
        }
        return ValidationResult.OK;
    }
}
