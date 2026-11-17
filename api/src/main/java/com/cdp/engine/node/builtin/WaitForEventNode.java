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

/**
 * 等事件（spec 03 WaitForEventNode）：首次執行回 WAIT(Event)；事件到達走 occurred、逾時走 timeout。event_filter 不符合的事件由派發端擋下（不喚醒），
 * 所以收到 EventArrived 一定是等到了。timeout 為 null = 不逾時。
 */
@Component
public final class WaitForEventNode implements JourneyNode {

    @Override
    public String type() {
        return "WaitForEventNode";
    }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        return DataRequirements.NONE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeContext context) {
        String eventName = context.config().get("event_name", String.class);
        String timeout = context.config().get("timeout", String.class);
        Map<String, Object> evaluated = new LinkedHashMap<>();
        evaluated.put("event_name", eventName);
        if (timeout != null) {
            evaluated.put("duration", timeout);
        }
        WakeSignal signal = context.wakeSignal();
        if (signal instanceof WakeSignal.EventArrived arrived) {
            return NodeResult.advance(
                    "occurred",
                    List.of(),
                    "等到事件 " + eventName + "（發生於 " + arrived.occurredAt() + "）。",
                    evaluated);
        }
        if (signal != null) {
            Instant at =
                    signal instanceof WakeSignal.Timeout t
                            ? t.timedOutAt()
                            : ((WakeSignal.TimerFired) signal).firedAt();
            evaluated.put("wake_at", at.toString());
            return NodeResult.advance(
                    "timeout", List.of(), "等待事件 " + eventName + " 逾時，於 " + at + " 走逾時出口。", evaluated);
        }
        Instant timeoutAt = timeout == null ? null : context.now().plus(Duration.parse(timeout));
        Object filter = context.config().values().get("event_filter");
        Map<String, Object> filterMap = filter instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        String reason;
        if (timeoutAt == null) {
            reason = "等待事件 " + eventName + "，不設逾時。";
        } else {
            evaluated.put("wake_at", timeoutAt.toString());
            reason = "等待事件 " + eventName + "，最長 " + timeout + "，逾時時間 " + timeoutAt + "。";
        }
        return NodeResult.waitFor(new WakeCondition.Event(eventName, filterMap, timeoutAt), reason, evaluated);
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        String eventName = config.get("event_name", String.class);
        if (eventName == null || eventName.isBlank()) {
            return new ValidationResult(List.of("WaitForEventNode.config.event_name 必填"));
        }
        String timeout = config.get("timeout", String.class);
        if (timeout != null) {
            try {
                if (Duration.parse(timeout).compareTo(Duration.ZERO) <= 0) {
                    return new ValidationResult(List.of("WaitForEventNode.config.timeout 須為正的期間"));
                }
            } catch (DateTimeParseException e) {
                return new ValidationResult(List.of("WaitForEventNode.config.timeout 不是合法的 ISO-8601 Duration"));
            }
        }
        return ValidationResult.OK;
    }
}
