package com.cdp.engine.runtime;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.JourneyNode;

/** 依 type 取節點實作（所有 JourneyNode bean）。 */
@Component
public class NodeRegistry {

    private final List<JourneyNode> nodes;

    public NodeRegistry(List<JourneyNode> nodes) {
        this.nodes = nodes;
    }

    /** 找不到型別時丟 IllegalArgumentException。 */
    public JourneyNode get(String type) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    public boolean supports(String type) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
