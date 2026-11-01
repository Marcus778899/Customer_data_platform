package com.cdp.ingest;

import java.time.Instant;
import java.util.Map;

/** 攝取請求的一筆事件（01 §3）。 */
public record EventRequest(
        String eventId,
        SourceType sourceType,
        String sourceId,
        String name,
        Instant occurredAt,
        EventIdentity identity,
        Map<String, Object> properties,
        EventContext context) {}
