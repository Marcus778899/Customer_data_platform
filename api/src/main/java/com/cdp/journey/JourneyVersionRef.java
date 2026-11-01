package com.cdp.journey;

import java.time.Instant;

/** journeys.md §1 {@code JourneyDetail.versions[]}。 */
public record JourneyVersionRef(int version, Instant publishedAt) {}
