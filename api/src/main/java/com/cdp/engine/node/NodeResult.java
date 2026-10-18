package com.cdp.engine.node;

import java.util.List;
import java.util.Map;

import com.cdp.engine.model.WakeCondition;

/** 節點回傳（04 §4）。decisionReason、evaluated 必填，格式見 docs/contracts/journey-engine.md §4.3。 */
public record NodeResult(
        ResultStatus status,
        String outPort,
        WakeCondition wakeCondition,
        Map<String, Object> variablePatch,
        List<Effect> effects,
        String decisionReason,
        Map<String, Object> evaluated,
        String terminationReason) {

    public static NodeResult advance(
            String outPort,
            List<Effect> effects,
            String decisionReason,
            Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.ADVANCE,
                outPort,
                null,
                null,
                effects == null ? List.of() : effects,
                decisionReason,
                evaluated,
                null);
    }

    public static NodeResult waitFor(
            WakeCondition wakeCondition, String decisionReason, Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.WAIT,
                null,
                wakeCondition,
                null,
                List.of(),
                decisionReason,
                evaluated,
                null);
    }

    public static NodeResult complete(
            String terminationReason, String decisionReason, Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.COMPLETE,
                null,
                null,
                null,
                List.of(),
                decisionReason,
                evaluated,
                terminationReason);
    }
}
