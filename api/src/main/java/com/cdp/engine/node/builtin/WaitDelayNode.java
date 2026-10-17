package com.cdp.engine.node.builtin;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/** 等一段時間（03 §5）。首次執行回 WAIT(Timer)；wakeSignal=TimerFired 時回 ADVANCE "next"。 */
@Component
public final class WaitDelayNode implements JourneyNode {

    @Override
    public String type() {
        return "WaitDelayNode";
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
