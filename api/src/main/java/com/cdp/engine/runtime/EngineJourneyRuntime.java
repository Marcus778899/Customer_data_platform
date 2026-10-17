package com.cdp.engine.runtime;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.cdp.common.IdGenerator;
import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeSignal;

/** 自建路線的 JourneyRuntime。cancel／cancelAll 於 v0.3.0（G10）實作，v0.1.0 丟 UnsupportedOperationException。 */
@Service
public class EngineJourneyRuntime implements JourneyRuntime {

    private final StateStore stateStore;
    private final EnrollmentAdvancer advancer;
    private final IdGenerator ids;
    private final Clock clock;

    public EngineJourneyRuntime(
            StateStore stateStore, EnrollmentAdvancer advancer, IdGenerator ids, Clock clock) {
        this.stateStore = stateStore;
        this.advancer = advancer;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public EnrollmentState start(
            String tenantId,
            String journeyId,
            int version,
            String sourceNodeId,
            String profileId,
            TriggerSnapshot trigger,
            String dedupKey) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void signal(String tenantId, String enrollmentId, WakeSignal signal) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void cancel(String tenantId, String enrollmentId, String reason) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }

    @Override
    public void cancelAll(String tenantId, String journeyId, String reason) {
        throw new UnsupportedOperationException("v0.3.0 (G10)");
    }

    @Override
    public Optional<EnrollmentState> get(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
