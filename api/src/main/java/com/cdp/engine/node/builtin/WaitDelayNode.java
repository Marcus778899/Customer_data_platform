package com.cdp.engine.node.builtin;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
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
        String duration = context.config().get("duration", String.class);
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("duration", duration);
        if (context.wakeSignal() instanceof WakeSignal.TimerFired fired) {
            evaluated.put("wake_at", fired.firedAt().toString());
            return NodeResult.advance(
                    "next",
                    List.of(),
                    "等待 " + duration + " 已結束，於 " + fired.firedAt() + " 繼續。",
                    evaluated);
        }
        Instant wakeAt = context.now().plus(Duration.parse(duration));
        evaluated.put("wake_at", wakeAt.toString());
        return NodeResult.waitFor(
                new WakeCondition.Timer(wakeAt),
                "等待 " + duration + "，預計 " + wakeAt + " 喚醒。",
                evaluated);
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        String duration = config.get("duration", String.class);
        if (duration == null || duration.isBlank()) {
            return new ValidationResult(List.of("WaitDelayNode.config.duration 必填（ISO-8601 Duration）"));
        }
        try {
            if (Duration.parse(duration).compareTo(Duration.ZERO) <= 0) {
                return new ValidationResult(List.of("WaitDelayNode.config.duration 須為正的期間"));
            }
        } catch (DateTimeParseException e) {
            return new ValidationResult(List.of("WaitDelayNode.config.duration 不是合法的 ISO-8601 Duration"));
        }
        return ValidationResult.OK;
    }
}
