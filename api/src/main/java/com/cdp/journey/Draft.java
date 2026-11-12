package com.cdp.journey;

import java.time.Instant;
import java.util.Map;

/** journeys.md §3 {@code Draft}。definition 為 name、description、settings、entry_node、nodes。 */
public record Draft(
        String journeyId,
        int revision,
        Map<String, Object> definition,
        Map<String, Object> layout,
        Instant updatedAt,
        UserRef updatedBy,
        JourneyReview latestReview) {}
