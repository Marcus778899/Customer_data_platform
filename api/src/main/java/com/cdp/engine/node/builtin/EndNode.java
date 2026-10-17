package com.cdp.engine.node.builtin;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/** 終點（03 §5）。回 COMPLETE，Enrollment 轉 COMPLETED。 */
@Component
public final class EndNode implements JourneyNode {

    @Override
    public String type() {
        return "EndNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
