package com.cdp.engine.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.model.WakeSignal;
import com.cdp.engine.node.condition.ConditionEvaluator;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;

/**
 * 事件到達時對該客戶「未結束的 Enrollment」的反應（DESIGN-03 做法 #8）：先看 settings.goal／global_exit 的事件條件（命中即轉終態），
 * 否則若正在等這個事件且 event_filter 符合就喚醒。每筆先 lockById 再處理，與計時器喚醒互斥；單筆失敗不影響其他筆，回傳第一個失敗由呼叫端決定重試。
 * 建立該事件自己的 Enrollment 不算（觸發事件不能同時是它的目標事件）。
 */
@Component
public class EventReactor {

    private static final Logger log = LoggerFactory.getLogger(EventReactor.class);

    private final StateStore stateStore;
    private final JourneyDefinitionStore journeys;
    private final EnrollmentAdvancer advancer;

    public EventReactor(
            StateStore stateStore, JourneyDefinitionStore journeys, EnrollmentAdvancer advancer) {
        this.stateStore = stateStore;
        this.journeys = journeys;
        this.advancer = advancer;
    }

    /** 回傳 null = 全部成功；否則為第一個失敗。 */
    public RuntimeException react(
            String profileId,
            String eventId,
            String eventName,
            Instant occurredAt,
            Map<String, Object> properties) {
        RuntimeException first = null;
        List<EnrollmentState> open = stateStore.findOpenByProfile(profileId);
        for (EnrollmentState candidate : open) {
            if (candidate.triggerSnapshot() != null
                    && eventId.equals(candidate.triggerSnapshot().eventId())) {
                continue;
            }
            try {
                reactOne(candidate.enrollmentId(), eventName, occurredAt, properties);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
                log.error(
                        "event reaction failed event_id={} enrollment_id={}",
                        eventId,
                        candidate.enrollmentId(),
                        e);
            }
        }
        return first;
    }

    private void reactOne(
            String enrollmentId, String eventName, Instant occurredAt, Map<String, Object> properties) {
        EnrollmentState state = stateStore.lockById(enrollmentId).orElse(null);
        if (state == null || state.status().isTerminal()) {
            return;
        }
        JourneyDefinition journey =
                journeys.find(state.journeyId(), state.journeyVersion()).orElse(null);
        if (journey == null) {
            return;
        }
        Optional<SettingsEvaluator.Termination> hit =
                SettingsEvaluator.evaluateEvent(
                        journey.settings(), state.enrolledAt(), eventName, occurredAt);
        if (hit.isPresent()) {
            advancer.terminateBySettings(state, hit.get().status(), hit.get().reason());
            return;
        }
        if (state.wakeCondition() instanceof WakeCondition.Event wait
                && wait.eventName().equals(eventName)
                // 事件發生在逾時之後不算等到，留給 AdvanceLoop 送 Timeout（spec 01 §6）
                && (wait.timeoutAt() == null || !occurredAt.isAfter(wait.timeoutAt()))
                && ConditionEvaluator.eventMatches(wait.filter(), properties)) {
            advancer.advance(state, new WakeSignal.EventArrived(eventName, properties, occurredAt));
        }
    }
}
