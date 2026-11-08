package com.cdp.engine.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.delivery.OutboxStore;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.journey.JourneyStore;
import com.cdp.journey.JourneyStore.StoppedEnrollment;

/**
 * 停止旅程的背景清理（journeys.md §3.5、spec 05 §5 步驟 4、5）：分批把已停止旅程中進行中的 Enrollment 轉 ABORTED，
 * 每筆寫一列軌跡、取消其待送的 outbox。每批一個小交易（FOR UPDATE SKIP LOCKED），失敗只影響該批、下輪重試；
 * 滿批續做到 CDP_LOOP_MAX_DRAIN。
 */
@Component
public class JourneyStopSweeper {

    private static final Logger log = LoggerFactory.getLogger(JourneyStopSweeper.class);
    private static final int MAX_REASON = 200;
    private static final String PREFIX = "旅程已停止：";

    private final JourneyStore journeys;
    private final JourneyDefinitionStore definitions;
    private final StateStore stateStore;
    private final OutboxStore outbox;
    private final EngineProperties properties;
    private final LoopProperties loop;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JourneyStopSweeper(
            JourneyStore journeys,
            JourneyDefinitionStore definitions,
            StateStore stateStore,
            OutboxStore outbox,
            EngineProperties properties,
            LoopProperties loop,
            TransactionTemplate tx,
            Clock clock) {
        this.journeys = journeys;
        this.definitions = definitions;
        this.stateStore = stateStore;
        this.outbox = outbox;
        this.properties = properties;
        this.loop = loop;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cdp.engine.poll-interval}")
    public void tick() {
        long deadline = System.nanoTime() + loop.maxDrain().toNanos();
        int n;
        do {
            try {
                n = runOnce();
            } catch (RuntimeException e) {
                log.error("journey stop sweep failed", e);
                return;
            }
        } while (n >= properties.batchSize() && System.nanoTime() < deadline);
    }

    /** 清理一批（最多 batchSize 筆），回傳處理筆數（測試可直接呼叫）。 */
    public int runOnce() {
        Integer done = tx.execute(status -> sweepBatch());
        return done == null ? 0 : done;
    }

    private int sweepBatch() {
        List<StoppedEnrollment> batch = journeys.lockOpenEnrollmentsOfStoppedJourneys(properties.batchSize());
        Instant now = clock.instant();
        int done = 0;
        for (StoppedEnrollment item : batch) {
            EnrollmentState state = stateStore.findById(item.enrollmentId()).orElse(null);
            if (state == null || state.status().isTerminal()) {
                continue;
            }
            abort(state, item.stopReason(), now);
            done++;
        }
        return done;
    }

    private void abort(EnrollmentState state, String stopReason, Instant now) {
        String reason = reason(stopReason);
        String nodeType =
                definitions
                        .find(state.journeyId(), state.journeyVersion())
                        .flatMap(d -> d.node(state.currentNodeId()))
                        .map(JourneyNodeDefinition::type)
                        .orElse("Unknown");
        EnrollmentState ended =
                new EnrollmentState(
                        state.enrollmentId(),
                        state.journeyId(),
                        state.journeyVersion(),
                        state.profileId(),
                        EnrollmentStatus.ABORTED,
                        state.currentNodeId(),
                        null,
                        state.triggerSnapshot(),
                        state.dedupKey(),
                        state.variables(),
                        state.deliveryHistory(),
                        state.enrolledAt(),
                        now,
                        now,
                        reason);
        stateStore.save(
                ended,
                new EnrollmentTrace(
                        state.enrollmentId(),
                        DefaultEnrollmentAdvancer.APPEND_TRACE,
                        state.currentNodeId(),
                        nodeType,
                        now,
                        now,
                        null,
                        reason,
                        Map.of()),
                List.of());
        outbox.cancelPendingByEnrollment(state.enrollmentId());
    }

    private static String reason(String stopReason) {
        String full = PREFIX + (stopReason == null ? "" : stopReason);
        return full.length() <= MAX_REASON ? full : full.substring(0, MAX_REASON);
    }
}
