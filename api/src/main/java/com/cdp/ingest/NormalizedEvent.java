package com.cdp.ingest;

import java.time.Instant;
import java.util.Map;

/** 落地後的事件（表 event）；receivedAt 取自 Clock。 */
public record NormalizedEvent(
        String eventId,
        String tenantId,
        SourceType sourceType,
        String sourceId,
        String name,
        Instant occurredAt,
        Instant receivedAt,
        EventIdentity identity,
        Map<String, Object> properties,
        EventContext context) {}
