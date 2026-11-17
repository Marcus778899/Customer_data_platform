package com.cdp.engine.runtime;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.node.condition.ConditionEvaluator;

/**
 * settings.goal／global_exit 求值（spec 03 §3、04 §6 步驟 3；journeys.md 第 4 節）。純函式。
 *
 * <p>event 條件在事件派發時對該客戶未結束的 Enrollment 求值（occurred_at 不早於 enrolled_at）；attribute 條件在每次推進時求值。原因句只引用設定值，
 * 不引用屬性值。
 */
public final class SettingsEvaluator {

    private static final int MAX_REASON = 200;

    private SettingsEvaluator() {}

    /** 命中結果：終態與給人看的原因。 */
    public record Termination(EnrollmentStatus status, String reason) {}

    /** 推進時：global_exit 的 attribute 條件。 */
    public static Optional<Termination> evaluateAttributes(
            Map<String, Object> settings, Function<String, Object> resolver) {
        Map<?, ?> globalExit = map(settings, "global_exit");
        for (Map<?, ?> c : conditions(settings)) {
            if ("attribute".equals(c.get("type"))
                    && ConditionEvaluator.evaluate(c, resolver).matched()) {
                return Optional.of(
                        new Termination(
                                onMatch(c, globalExit), "符合全域退出條件（客戶資料條件），旅程結束。"));
            }
        }
        return Optional.empty();
    }

    /** 事件到達時：settings.goal 優先，其次 global_exit 的 event 條件。 */
    public static Optional<Termination> evaluateEvent(
            Map<String, Object> settings, Instant enrolledAt, String eventName, Instant occurredAt) {
        if (eventName == null || occurredAt == null || occurredAt.isBefore(enrolledAt)) {
            return Optional.empty();
        }
        Map<?, ?> goal = map(settings, "goal");
        if (goal != null && goal.get("condition") instanceof Map<?, ?> cond
                && "event".equals(cond.get("type"))
                && eventName.equals(cond.get("event"))
                && withinWindow(goal.get("window"), enrolledAt, occurredAt)) {
            return Optional.of(
                    new Termination(
                            EnrollmentStatus.GOAL_MET,
                            cut("收到目標事件 " + eventName + "，旅程目標已達成。")));
        }
        Map<?, ?> globalExit = map(settings, "global_exit");
        for (Map<?, ?> c : conditions(settings)) {
            if ("event".equals(c.get("type")) && eventName.equals(c.get("event"))) {
                EnrollmentStatus status = onMatch(c, globalExit);
                return Optional.of(
                        new Termination(
                                status,
                                cut(
                                        "收到全域退出事件 "
                                                + eventName
                                                + (status == EnrollmentStatus.GOAL_MET
                                                        ? "，視為達標。"
                                                        : "，客戶已移出旅程。"))));
            }
        }
        return Optional.empty();
    }

    private static boolean withinWindow(Object window, Instant enrolledAt, Instant occurredAt) {
        if (!(window instanceof String w)) {
            return true;
        }
        try {
            return !occurredAt.isAfter(enrolledAt.plus(Duration.parse(w)));
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private static EnrollmentStatus onMatch(Map<?, ?> condition, Map<?, ?> globalExit) {
        Object v = condition.get("on_match");
        if (v == null && globalExit != null) {
            v = globalExit.get("on_match");
        }
        return "GOAL_MET".equals(v) ? EnrollmentStatus.GOAL_MET : EnrollmentStatus.EXITED;
    }

    private static List<Map<?, ?>> conditions(Map<String, Object> settings) {
        Map<?, ?> globalExit = map(settings, "global_exit");
        if (globalExit == null || !(globalExit.get("conditions") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).<Map<?, ?>>map(o -> (Map<?, ?>) o).toList();
    }

    private static Map<?, ?> map(Map<String, Object> settings, String key) {
        return settings != null && settings.get(key) instanceof Map<?, ?> m ? m : null;
    }

    private static String cut(String s) {
        return s.length() <= MAX_REASON ? s : s.substring(0, MAX_REASON);
    }
}
