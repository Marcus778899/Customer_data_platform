package com.cdp.engine.runtime;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cdp.engine.StateStore;

/** 計時器喚醒（R1）：每 cdp.engine.poll-interval 以 Clock 的現在時間 lockDue，逐筆 advance(TimerFired)。 */
@Component
public class AdvanceLoop {

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
    public void tick() {
        runOnce();
    }

    /** 推進一批到期 Enrollment，回傳筆數（測試可直接呼叫）。 */
    public int runOnce() {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
