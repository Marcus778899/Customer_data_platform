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
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    public static NodeResult waitFor(
            WakeCondition wakeCondition, String decisionReason, Map<String, Object> evaluated) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    public static NodeResult complete(
            String terminationReason, String decisionReason, Map<String, Object> evaluated) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
