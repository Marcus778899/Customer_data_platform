package com.cdp.engine;

import java.util.Optional;

import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeSignal;

/**
 * 引擎抽象（docs/spec/04-node-contract.md §7）；換 Temporal 時整組替換。 start 多帶 sourceNodeId：命中的
 * EventSourceNode，Enrollment 從它開始推進。
 */
public interface JourneyRuntime {

    EnrollmentState start(
            String journeyId,
            int version,
            String sourceNodeId,
            String profileId,
            TriggerSnapshot trigger,
            String dedupKey);

    void signal(String enrollmentId, WakeSignal signal);

    void cancel(String enrollmentId, String reason);

    void cancelAll(String journeyId, String reason);

    Optional<EnrollmentState> get(String enrollmentId);
}
