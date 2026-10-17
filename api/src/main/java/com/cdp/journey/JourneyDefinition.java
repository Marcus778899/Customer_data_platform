package com.cdp.journey;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 旅程定義某一版（03 §2；表 journey_definition）。 */
public record JourneyDefinition(
        String journeyId,
        String tenantId,
        int version,
        String name,
        String description,
        JourneyStatus status,
        Map<String, Object> settings,
        String entryNode,
        List<JourneyNodeDefinition> nodes,
        Instant createdAt,
        Instant publishedAt) {

    public Optional<JourneyNodeDefinition> node(String nodeId) {
        if (nodes == null || nodeId == null) {
            return Optional.empty();
        }
        return nodes.stream().filter(n -> nodeId.equals(n.id())).findFirst();
    }
}
