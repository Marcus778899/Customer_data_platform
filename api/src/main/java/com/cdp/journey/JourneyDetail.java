package com.cdp.journey;

import java.time.Instant;
import java.util.List;

/** journeys.md §1 {@code JourneyDetail}；{@code stoppedBy}（UserRef）第二版才有值，本版恆為 null。 */
public record JourneyDetail(
        String journeyId,
        String name,
        JourneyStatus status,
        Integer latestVersion,
        boolean hasDraft,
        String reviewStatus,
        Instant updatedAt,
        Instant publishedAt,
        String description,
        List<JourneyVersionRef> versions,
        Instant stoppedAt,
        String stopReason,
        Object stoppedBy) {}
