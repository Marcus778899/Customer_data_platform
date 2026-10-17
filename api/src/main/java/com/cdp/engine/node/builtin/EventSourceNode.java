package com.cdp.engine.node.builtin;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/** 事件入口（03 §5）。Enrollment 由此開始；v0.1.0 只比對 event_name，event_filter／profile_filter 須為 null。 */
@Component
public final class EventSourceNode implements JourneyNode {

    @Override
    public String type() {
        return "EventSourceNode";
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
