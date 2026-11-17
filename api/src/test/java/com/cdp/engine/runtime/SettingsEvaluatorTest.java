package com.cdp.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.node.condition.ConditionEvaluator;

class SettingsEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static final Map<String, Object> SETTINGS =
            Map.of(
                    "goal", Map.of("condition", Map.of("type", "event", "event", "paid"), "window", "P1D"),
                    "global_exit",
                            Map.of(
                                    "on_match", "EXITED",
                                    "conditions",
                                            List.of(
                                                    Map.of("type", "event", "event", "bye", "on_match", "GOAL_MET"),
                                                    Map.of("type", "event", "event", "unsub"),
                                                    Map.of("type", "attribute", "field", "opt_out", "operator", "EQ", "value", true))));

    @Test
    void goalHitsWithinWindowOnly() {
        var hit = SettingsEvaluator.evaluateEvent(SETTINGS, T0, "paid", T0.plusSeconds(60));
        assertThat(hit).isPresent();
        assertThat(hit.get().status()).isEqualTo(EnrollmentStatus.GOAL_MET);
        assertThat(SettingsEvaluator.evaluateEvent(SETTINGS, T0, "paid", T0.plusSeconds(86401))).isEmpty();
        assertThat(SettingsEvaluator.evaluateEvent(SETTINGS, T0, "paid", T0.minusSeconds(1))).isEmpty();
        assertThat(SettingsEvaluator.evaluateEvent(SETTINGS, T0, "other", T0)).isEmpty();
        assertThat(SettingsEvaluator.evaluateEvent(SETTINGS, T0, null, T0)).isEmpty();
    }

    @Test
    void globalExitEventOverridesOnMatchPerCondition() {
        assertThat(SettingsEvaluator.evaluateEvent(SETTINGS, T0, "bye", T0).get().status())
                .isEqualTo(EnrollmentStatus.GOAL_MET);
        var unsub = SettingsEvaluator.evaluateEvent(SETTINGS, T0, "unsub", T0).get();
        assertThat(unsub.status()).isEqualTo(EnrollmentStatus.EXITED);
        assertThat(unsub.reason()).isNotBlank().hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void attributeConditionEvaluatedOnAdvance() {
        var yes =
                SettingsEvaluator.evaluateAttributes(
                        SETTINGS, ConditionEvaluator.progressResolver(Map.of("opt_out", true), Map.of()));
        assertThat(yes.get().status()).isEqualTo(EnrollmentStatus.EXITED);
        assertThat(SettingsEvaluator.evaluateAttributes(
                        SETTINGS, ConditionEvaluator.progressResolver(Map.of(), Map.of())))
                .isEmpty();
        assertThat(SettingsEvaluator.evaluateAttributes(null, f -> null)).isEmpty();
        assertThat(SettingsEvaluator.evaluateEvent(Map.of(), T0, "paid", T0)).isEmpty();
    }

    @Test
    void badWindowIsIgnored() {
        Map<String, Object> s =
                Map.of("goal", Map.of("condition", Map.of("type", "event", "event", "paid"), "window", "zz"));
        assertThat(SettingsEvaluator.evaluateEvent(s, T0, "paid", T0.plusSeconds(999999))).isPresent();
    }
}
