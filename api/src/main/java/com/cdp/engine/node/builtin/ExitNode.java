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

/** 退出（03 §5）：Enrollment 轉 EXITED，terminationReason 含設定的 reason（config 文字，不是屬性值）。 */
@Component
public final class ExitNode implements JourneyNode {

    @Override
    public String type() {
        return "ExitNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String reason = context.config().get("reason", String.class);
        String text = "旅程於退出節點 " + context.nodeId() + " 結束：" + (reason == null ? "" : reason);
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        return NodeResult.exit(text, text, Map.of());
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        String reason = config.get("reason", String.class);
        if (reason == null || reason.isBlank()) {
            return new ValidationResult(List.of("ExitNode.config.reason 必填"));
        }
        return ValidationResult.OK;
    }
}
