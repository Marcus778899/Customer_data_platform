package com.cdp.ingest;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cdp.engine.JourneyRuntime;
import com.cdp.engine.runtime.EngineProperties;
import com.cdp.journey.JourneyDefinitionStore;

/**
 * A 類扇出（Postgres 輪詢）：同一交易內 lockUndispatched → 對每個命中的 EventSourceBinding 呼叫
 * JourneyRuntime.start → markDispatched。遲到 > 24h 只標派發、不建立（01 §6）。
 */
@Component
public class EventDispatcher {

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
    public void tick() {
        runOnce();
    }

    /** 派發一批，回傳事件筆數（測試可直接呼叫）。 */
    public int runOnce() {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
