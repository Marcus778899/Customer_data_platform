package com.cdp.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.delivery.LoopProperties;
import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.runtime.EngineProperties;
import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinitionStore;

/**
 * A 類扇出（Postgres 輪詢）：每個事件一個交易（DESIGN-03「relay 與迴圈」）：lockUndispatched → 對每個命中的
 * EventSourceBinding 呼叫 JourneyRuntime.start → markDispatched。遲到 > 24h 只標 LATE、不建立（01 §6）。tick 滿批
 * 續做到 CDP_LOOP_MAX_DRAIN；失敗的事件本輪略過。
 *
 * <p>start 為 NESTED，單一綁定失敗只回滾它自己；該事件記一次失敗並依指數退避延後，達上限標 EXHAUSTED。已建立的
 * Enrollment 靠 dedup_key（= event_id）去重，重試時只補失敗的綁定。規則見 spec 07 §11。
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
    private static final Duration LATE_THRESHOLD = Duration.ofHours(24);
    private static final String EXHAUSTED = "EXHAUSTED";

    private final EventStore events;
    private final JourneyDefinitionStore journeys;
    private final JourneyRuntime runtime;
    private final EngineProperties properties;
    private final DispatchProperties dispatchProperties;
    private final LoopProperties loop;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EventDispatcher(
            EventStore events,
            JourneyDefinitionStore journeys,
            JourneyRuntime runtime,
            EngineProperties properties,
            DispatchProperties dispatchProperties,
            LoopProperties loop,
            TransactionTemplate tx,
            Clock clock) {
        this.events = events;
        this.journeys = journeys;
        this.runtime = runtime;
        this.properties = properties;
        this.dispatchProperties = dispatchProperties;
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

    /** 派發一批（最多 batchSize 個事件，每個事件一個交易），回傳事件筆數（測試可直接呼叫）。 */
    public int runOnce() {
        return runBatch(new HashSet<>());
    }

    private int runBatch(Set<String> failed) {
        int processed = 0;
        while (processed < properties.batchSize()) {
            Boolean ok = tx.execute(status -> dispatchNext(failed));
            if (ok == null) {
                break; // 沒有可派發的事件
            }
            processed++;
        }
        return processed;
    }

    /** 一個交易：鎖一個事件（略過本輪失敗者）並派發。回傳 null = 沒有可處理的事件。 */
    private Boolean dispatchNext(Set<String> failed) {
        Instant now = clock.instant();
        // EventStore.lockUndispatched 沒有排除清單：多取「失敗數 + 1」列，取第一個沒失敗過的
        List<UndispatchedEvent> rows = events.lockUndispatched(failed.size() + 1, now);
        for (UndispatchedEvent row : rows) {
            if (failed.contains(row.eventId())) {
                continue;
            }
            NormalizedEvent event = row.event();
            if (event == null) {
                events.recordFailure(row.eventId(), row.parseError());
                events.markDispatched(row.eventId(), now, EXHAUSTED);
                log.error("event row unparsable, giving up event_id={}", row.eventId());
                return Boolean.TRUE;
            }
            if (isLate(event)) {
                events.markDispatched(event.eventId(), now, "LATE");
                return Boolean.TRUE;
            }
            RuntimeException failure = dispatch(event);
            if (failure == null) {
                events.markDispatched(event.eventId(), now, "DISPATCHED");
                return Boolean.TRUE;
            }
            failed.add(event.eventId());
            onFailure(event, failure, now);
            return Boolean.FALSE;
        }
        return null;
    }

    private void onFailure(NormalizedEvent event, RuntimeException failure, Instant now) {
        int attempts =
                events.recordFailure(
                        event.eventId(),
                        failure.getClass().getSimpleName() + ": " + failure.getMessage());
        if (attempts >= dispatchProperties.maxAttempts()) {
            events.markDispatched(event.eventId(), now, EXHAUSTED);
            log.error(
                    "dispatch exhausted after {} attempts event_id={}", attempts, event.eventId());
        } else {
            events.scheduleRetry(event.eventId(), now.plus(backoff(attempts)));
        }
    }

    /** min(初始 × 2^(attempts−1), 上限)；先與上限比較再相乘，避免 Duration 溢位。 */
    Duration backoff(int attempts) {
        Duration max = dispatchProperties.backoffMax();
        Duration delay = dispatchProperties.backoffInitial();
        for (int i = 1; i < attempts; i++) {
            if (delay.compareTo(max.dividedBy(2)) > 0) {
                return max;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static boolean isLate(NormalizedEvent event) {
        return Duration.between(event.occurredAt(), event.receivedAt()).compareTo(LATE_THRESHOLD)
                > 0;
    }

    /** 回傳 null 表示全部綁定成功（或無綁定）；否則為第一個失敗。 */
    private RuntimeException dispatch(NormalizedEvent event) {
        List<EventSourceBinding> bindings =
                journeys.findActiveEventSources(event.name());
        RuntimeException first = null;
        for (EventSourceBinding binding : bindings) {
            try {
                runtime.start(
                        binding.journeyId(),
                        binding.version(),
                        binding.sourceNodeId(),
                        event.identity().profileId(),
                        snapshot(event),
                        event.eventId());
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
                log.error(
                        "dispatch failed event_id={} journey_id={}",
                        event.eventId(),
                        binding.journeyId(),
                        e);
            }
        }
        return first;
    }

    private static TriggerSnapshot snapshot(NormalizedEvent event) {
        return new TriggerSnapshot(
                event.eventId(),
                event.name(),
                event.occurredAt(),
                event.sourceType().name(),
                event.properties() == null ? Map.of() : event.properties(),
                Map.of());
    }
}
