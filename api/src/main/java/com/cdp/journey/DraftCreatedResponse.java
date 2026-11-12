package com.cdp.journey;

public record DraftCreatedResponse(String journeyId, JourneyStatus status, int revision) {}
