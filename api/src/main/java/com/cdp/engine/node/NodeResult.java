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

    /** ADVANCE 並寫入旅程變數（例：A/B 分組結果）。 */
    public static NodeResult advanceWithVariables(
            String outPort,
            Map<String, Object> variablePatch,
            String decisionReason,
            Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.ADVANCE,
                outPort,
                null,
                variablePatch,
                List.of(),
                decisionReason,
                evaluated,
                null);
    }

    /** ExitNode：Enrollment 轉 EXITED。 */
    public static NodeResult exit(
            String terminationReason, String decisionReason, Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.EXIT,
                null,
                null,
                null,
                List.of(),
                decisionReason,
                evaluated,
                terminationReason);
    }

    /** GoalNode：Enrollment 轉 GOAL_MET。 */
    public static NodeResult goal(
            String terminationReason, String decisionReason, Map<String, Object> evaluated) {
        return new NodeResult(
                ResultStatus.GOAL,
                null,
                null,
                null,
                List.of(),
                decisionReason,
                evaluated,
                terminationReason);
    }
}
