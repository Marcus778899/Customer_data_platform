package com.cdp.engine.node.builtin;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.JourneyNode;
import com.cdp.engine.node.NodeConfig;
import com.cdp.engine.node.NodeContext;
import com.cdp.engine.node.NodeResult;
import com.cdp.engine.node.ValidationResult;
import com.cdp.journey.JourneyDefinition;

/**
 * 發送（03 §5）。回傳一個 Effect.SendMessage 並 ADVANCE "sent"（= 發送意圖已交給管線）；
 * v0.1.0 無守門，不會走 "skipped"（DESIGN-01 做法 #6）。
 */
@Component
public final class SendMessageNode implements JourneyNode {

    @Override
    public String type() {
        return "SendMessageNode";
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
