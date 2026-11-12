package com.cdp.journey;

import java.time.Instant;
import java.util.List;

/** journeys.md §1 {@code JourneyDetail}；stoppedBy 金鑰呼叫為 null。 */
public record JourneyDetail(
        String journeyId,
        String name,
        JourneyStatus status,
        Integer latestVersion,
        boolean hasDraft,
        String reviewStatus,
        Instant updatedAt,
        Instant publishedAt,
        ReviewBrief latestReview,
        String description,
        List<JourneyVersionRef> versions,
        Instant stoppedAt,
        String stopReason,
        UserRef stoppedBy) {}
