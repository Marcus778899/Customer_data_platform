package com.cdp.engine.node.builtin;

import java.util.ArrayList;
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
        String eventName = context.trigger().eventName();
        return NodeResult.advance(
                "next",
                List.of(),
                "收到事件 " + eventName + "，建立 Enrollment。",
                Map.of("event_id", context.trigger().eventId()));
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        List<String> errors = new ArrayList<>();
        String eventName = config.get("event_name", String.class);
        if (eventName == null || eventName.isBlank()) {
            errors.add("EventSourceNode.config.event_name 必填");
        }
        if (config.values() != null) {
            if (config.values().get("event_filter") != null) {
                errors.add("EventSourceNode.config.event_filter 在 v0.1.0 須為 null");
            }
            if (config.values().get("profile_filter") != null) {
                errors.add("EventSourceNode.config.profile_filter 在 v0.1.0 須為 null");
            }
        }
        return new ValidationResult(List.copyOf(errors));
    }
}
