package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.common.IdGenerator;
import com.cdp.engine.DuplicateEnrollmentException;
import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
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

    /**
     * 建立並首次推進，同一交易。dedupKey 撞 uq_enrollment_dedup 時回傳既有實例（事件重新派發因此冪等）。 NESTED：失敗只回滾這一筆，呼叫端可逐筆隔離。
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public EnrollmentState start(
            String tenantId,
            String journeyId,
            int version,
            String sourceNodeId,
            String profileId,
            TriggerSnapshot trigger,
            String dedupKey) {
        Instant now = clock.instant();
        EnrollmentState created =
                new EnrollmentState(
                        ids.newId(),
                        tenantId,
                        journeyId,
                        version,
                        profileId,
                        EnrollmentStatus.ACTIVE,
                        sourceNodeId,
                        null,
                        trigger,
                        dedupKey,
                        Map.of(),
                        List.of(),
                        now,
                        now,
                        null,
                        null);
        try {
            created = stateStore.create(created);
        } catch (DuplicateEnrollmentException e) {
            return stateStore
                    .findById(tenantId, e.existingEnrollmentId())
                    .orElseThrow(() -> new IllegalStateException("duplicate enrollment vanished"));
        }
        return advancer.advance(created, null);
    }

    @Override
    @Transactional
    public void signal(String tenantId, String enrollmentId, WakeSignal signal) {
        stateStore
                .findById(tenantId, enrollmentId)
                .filter(s -> s.status() == EnrollmentStatus.WAITING)
                .ifPresent(s -> advancer.advance(s, signal));
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
        return stateStore.findById(tenantId, enrollmentId);
    }
}
