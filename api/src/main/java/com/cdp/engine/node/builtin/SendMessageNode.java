package com.cdp.engine.node.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cdp.engine.node.DataRequirements;
import com.cdp.engine.node.Effect;
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
        String templateId = context.config().get("template_id", String.class);
        String channelId = context.config().get("channel_id", String.class);
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("template_id", templateId);
        evaluated.put("channel_id", channelId);
        // 冪等鍵一律填 null，由引擎蓋章（04 §5）
        Effect effect = new Effect.SendMessage(null, templateId, channelId, Map.of());
        return NodeResult.advance(
                "sent",
                List.of(effect),
                "已將模板 " + templateId + " 經通道 " + channelId + " 的發送排入管線。",
                evaluated);
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        List<String> errors = new ArrayList<>();
        String templateId = config.get("template_id", String.class);
        if (templateId == null || templateId.isBlank()) {
            errors.add("SendMessageNode.config.template_id 必填");
        }
        String channelId = config.get("channel_id", String.class);
        if (channelId == null || channelId.isBlank()) {
            errors.add("SendMessageNode.config.channel_id 必填");
        }
        return new ValidationResult(List.copyOf(errors));
    }
}
