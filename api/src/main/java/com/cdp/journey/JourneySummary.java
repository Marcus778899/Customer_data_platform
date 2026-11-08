package com.cdp.journey;

import java.time.Instant;

/** journeys.md §1 {@code JourneySummary}（第二版讀 journey 表，§2）。 */
public record JourneySummary(
        String journeyId,
        String name,
        JourneyStatus status,
        Integer latestVersion,
        boolean hasDraft,
        String reviewStatus,
        Instant updatedAt,
        Instant publishedAt,
        ReviewBrief latestReview) {}
