package com.cdp.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.runtime.EngineProperties;
import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinitionStore;

/**
 * A 類扇出（Postgres 輪詢）：同一交易內 lockUndispatched → 對每個命中的 EventSourceBinding 呼叫
 * JourneyRuntime.start → markDispatched。遲到 > 24h 只標 LATE、不建立（01 §6）。
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
    private final Clock clock;

    public EventDispatcher(
            EventStore events,
            JourneyDefinitionStore journeys,
            JourneyRuntime runtime,
            EngineProperties properties,
            DispatchProperties dispatchProperties,
            Clock clock) {
        this.events = events;
        this.journeys = journeys;
        this.runtime = runtime;
        this.properties = properties;
        this.dispatchProperties = dispatchProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cdp.engine.poll-interval}")
    @Transactional
    public void tick() {
        runOnce();
    }

    /** 派發一批，回傳事件筆數（測試可直接呼叫）。 */
    @Transactional
    public int runOnce() {
        Instant now = clock.instant();
        List<UndispatchedEvent> batch = events.lockUndispatched(properties.batchSize(), now);
        for (UndispatchedEvent row : batch) {
            NormalizedEvent event = row.event();
            if (event == null) {
                events.recordFailure(row.tenantId(), row.eventId(), row.parseError());
                events.markDispatched(row.tenantId(), row.eventId(), now, EXHAUSTED);
                log.error("event row unparsable, giving up event_id={}", row.eventId());
                continue;
            }
            if (isLate(event)) {
                events.markDispatched(event.tenantId(), event.eventId(), now, "LATE");
                continue;
            }
            RuntimeException failure = dispatch(event);
            if (failure == null) {
                events.markDispatched(event.tenantId(), event.eventId(), now, "DISPATCHED");
            } else {
                onFailure(event, failure, now);
            }
        }
        return batch.size();
    }

    private void onFailure(NormalizedEvent event, RuntimeException failure, Instant now) {
        int attempts =
                events.recordFailure(
                        event.tenantId(),
                        event.eventId(),
                        failure.getClass().getSimpleName() + ": " + failure.getMessage());
        if (attempts >= dispatchProperties.maxAttempts()) {
            events.markDispatched(event.tenantId(), event.eventId(), now, EXHAUSTED);
            log.error(
                    "dispatch exhausted after {} attempts event_id={}", attempts, event.eventId());
        } else {
            events.scheduleRetry(event.tenantId(), event.eventId(), now.plus(backoff(attempts)));
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
                journeys.findActiveEventSources(event.tenantId(), event.name());
        RuntimeException first = null;
        for (EventSourceBinding binding : bindings) {
            try {
                runtime.start(
                        event.tenantId(),
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
