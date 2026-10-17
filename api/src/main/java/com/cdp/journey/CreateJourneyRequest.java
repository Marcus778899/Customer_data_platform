package com.cdp.journey;

import java.util.List;
import java.util.Map;

/** POST /api/v1/journeys 的 body（契約 §3.1）。journeyId 為 null 表示新旅程。 */
public record CreateJourneyRequest(
        String journeyId,
        String name,
        String description,
        Map<String, Object> settings,
        String entryNode,
        List<JourneyNodeDefinition> nodes) {}
