package com.cdp.journey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** journeys.md §1 {@code JourneyVersion}；v0.1.0 建立的版本 {@code layout} 恆為 null。 */
public record JourneyVersionView(
        String journeyId,
        String name,
        int version,
        String description,
        Map<String, Object> settings,
        String entryNode,
        List<JourneyNodeDefinition> nodes,
        Object layout,
        Instant publishedAt) {}
