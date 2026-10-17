package com.cdp.journey;

import java.time.Instant;

public record JourneyCreatedResponse(
        String journeyId, int version, JourneyStatus status, Instant publishedAt) {}
