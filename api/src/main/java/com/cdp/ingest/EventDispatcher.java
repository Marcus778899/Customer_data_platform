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
 * JourneyRuntime.start → markDispatched。遲到 > 24h 只標派發、不建立（01 §6）。
 *
 * <p>start 為 NESTED，單一綁定失敗只回滾它自己；該事件不標派發、下輪重來，已建立的 Enrollment 靠 dedup_key（= event_id）
 * 去重，不會重複建立。
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
    private static final Duration LATE_THRESHOLD = Duration.ofHours(24);

    private final EventStore events;
    private final JourneyDefinitionStore journeys;
    private final JourneyRuntime runtime;
    private final EngineProperties properties;
    private final Clock clock;

    public EventDispatcher(
            EventStore events,
            JourneyDefinitionStore journeys,
            JourneyRuntime runtime,
            EngineProperties properties,
            Clock clock) {
        this.events = events;
        this.journeys = journeys;
        this.runtime = runtime;
        this.properties = properties;
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
        List<NormalizedEvent> batch = events.lockUndispatched(properties.batchSize());
        for (NormalizedEvent event : batch) {
            if (dispatch(event)) {
                events.markDispatched(event.tenantId(), event.eventId(), now);
            }
        }
        return batch.size();
    }

    /** 回傳 true 表示可標為已派發。 */
    private boolean dispatch(NormalizedEvent event) {
        boolean late =
                Duration.between(event.occurredAt(), event.receivedAt()).compareTo(LATE_THRESHOLD)
                        > 0;
        if (late) {
            return true;
        }
        List<EventSourceBinding> bindings =
                journeys.findActiveEventSources(event.tenantId(), event.name());
        boolean ok = true;
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
                ok = false;
                log.error(
                        "dispatch failed event_id={} journey_id={}",
                        event.eventId(),
                        binding.journeyId(),
                        e);
            }
        }
        return ok;
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
