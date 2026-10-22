package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.WakeSignal;

/**
 * 計時器喚醒（R1）：每 cdp.engine.poll-interval 以 Clock 的現在時間逐筆推進到期的 Enrollment。
 * 每一筆一個交易（lockDue → advance(TimerFired)，DESIGN-03「relay 與迴圈」）：失敗只回滾那一筆，本輪略過、下輪重試。
 * tick 滿批續做到 CDP_LOOP_MAX_DRAIN。
 */
@Component
public class AdvanceLoop {

    private static final Logger log = LoggerFactory.getLogger(AdvanceLoop.class);

    private final StateStore stateStore;
    private final EnrollmentAdvancer advancer;
    private final EngineProperties properties;
    private final LoopProperties loop;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AdvanceLoop(
            StateStore stateStore,
            EnrollmentAdvancer advancer,
            EngineProperties properties,
            LoopProperties loop,
            TransactionTemplate tx,
            Clock clock) {
        this.stateStore = stateStore;
        this.advancer = advancer;
        this.properties = properties;
        this.loop = loop;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cdp.engine.poll-interval}")
    public void tick() {
        long deadline = System.nanoTime() + loop.maxDrain().toNanos();
        Set<String> failed = new HashSet<>();
        int n;
        do {
            n = runBatch(failed);
        } while (n >= properties.batchSize() && System.nanoTime() < deadline);
    }

    /** 推進一批到期 Enrollment（最多 batchSize 筆），回傳處理筆數（測試可直接呼叫）。 */
    public int runOnce() {
        return runBatch(new HashSet<>());
    }

    private int runBatch(Set<String> failed) {
        Instant now = clock.instant();
        int processed = 0;
        while (processed < properties.batchSize()) {
            Boolean ok = tx.execute(status -> advanceNext(now, failed));
            if (ok == null) {
                break; // 沒有可處理的到期列
            }
            processed++;
        }
        return processed;
    }

    /** 一個交易：鎖一筆（略過本輪失敗者）並推進。回傳 null = 沒有可處理的列；true／false = 成功／失敗。 */
    private Boolean advanceNext(Instant now, Set<String> failed) {
        // StateStore.lockDue 沒有排除清單：多取「失敗數 + 1」列，取第一個沒失敗過的
        List<EnrollmentState> due = stateStore.lockDue(now, failed.size() + 1);
        for (EnrollmentState state : due) {
            if (failed.contains(state.enrollmentId())) {
                continue;
            }
            try {
                advancer.advance(state, new WakeSignal.TimerFired(now));
                return Boolean.TRUE;
            } catch (RuntimeException e) {
                failed.add(state.enrollmentId());
                log.error("advance failed enrollment_id={}", state.enrollmentId(), e);
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
