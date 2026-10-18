package com.cdp.delivery;

import java.time.Instant;
import java.util.Map;

/** 表 effect_outbox 一列（07 §10）。payload 為 Effect 序列化後的 JSON。 */
public record OutboxEntry(
        String idempotencyKey,
        String tenantId,
        String enrollmentId,
        String journeyId,
        int journeyVersion,
        String nodeId,
        long visitSeq,
        int effectIndex,
        String effectType,
        Map<String, Object> payload,
        OutboxStatus status,
        Instant availableAt,
        int publishCount,
        Instant createdAt,
        Instant publishedAt) {}
