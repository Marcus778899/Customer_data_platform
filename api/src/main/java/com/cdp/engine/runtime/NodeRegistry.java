package com.cdp.engine.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.JourneyNode;

/** 依 type 取節點實作（所有 JourneyNode bean）。 */
@Component
public class NodeRegistry {

    private final Map<String, JourneyNode> byType;

    public NodeRegistry(List<JourneyNode> nodes) {
        this.byType = nodes.stream().collect(Collectors.toMap(JourneyNode::type, Function.identity()));
    }

    /** 找不到型別時丟 IllegalArgumentException。 */
    public JourneyNode get(String type) {
        JourneyNode node = type == null ? null : byType.get(type);
        if (node == null) {
            throw new IllegalArgumentException("unknown node type: " + type);
        }
        return node;
    }

    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }
}
