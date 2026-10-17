package com.cdp.engine.node.builtin;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/** 圖的唯一根（03 §5），不推進 Enrollment；execute 不會被呼叫。 */
@Component
public final class StartNode implements JourneyNode {

    @Override
    public String type() {
        return "StartNode";
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
