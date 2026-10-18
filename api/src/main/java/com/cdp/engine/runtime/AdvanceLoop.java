package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.WakeSignal;

/**
 * 計時器喚醒（R1）：每 cdp.engine.poll-interval 以 Clock 的現在時間 lockDue，逐筆 advance(TimerFired)。
 * lockDue 的鎖與推進在同一交易；單筆失敗只回滾那一筆（advance 為 NESTED），其餘照常，失敗者留待下輪重試。
 */
@Component
public class AdvanceLoop {

    private static final Logger log = LoggerFactory.getLogger(AdvanceLoop.class);

    private final StateStore stateStore;
    private final EnrollmentAdvancer advancer;
    private final EngineProperties properties;
    private final Clock clock;

    public AdvanceLoop(
            StateStore stateStore,
            EnrollmentAdvancer advancer,
            EngineProperties properties,
            Clock clock) {
        this.stateStore = stateStore;
        this.advancer = advancer;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cdp.engine.poll-interval}")
    @Transactional
    public void tick() {
        runOnce();
    }

    /** 推進一批到期 Enrollment，回傳筆數（測試可直接呼叫）。 */
    @Transactional
    public int runOnce() {
        Instant now = clock.instant();
        List<EnrollmentState> due = stateStore.lockDue(now, properties.batchSize());
        int advanced = 0;
        for (EnrollmentState state : due) {
            try {
                advancer.advance(state, new WakeSignal.TimerFired(now));
                advanced++;
            } catch (RuntimeException e) {
                log.error("advance failed enrollment_id={}", state.enrollmentId(), e);
            }
        }
        return advanced;
    }
}
