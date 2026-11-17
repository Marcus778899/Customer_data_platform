package com.cdp.engine.node.builtin;

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

/** 合流（03 §5）：不做事，直接走 next。 */
@Component
public final class MergeNode implements JourneyNode {

    @Override
    public String type() {
        return "MergeNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        return NodeResult.advance("next", List.of(), "分支在節點 " + context.nodeId() + " 合流。", Map.of());
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        return ValidationResult.OK;
    }
}
