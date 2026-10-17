package com.cdp.engine.model;

import java.time.Instant;
import java.util.Map;

/** G1 觸發事件快照（02 §5）。v0.1.0 無 ProfileStore，profileSnapshot 一律為空 Map。 */
public record TriggerSnapshot(
        String eventId,
        String eventName,
        Instant occurredAt,
        String sourceType,
        Map<String, Object> properties,
        Map<String, Object> profileSnapshot) {}
