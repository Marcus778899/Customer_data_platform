package com.cdp.delivery;

import java.time.Instant;

import com.cdp.engine.model.MessageType;
import com.cdp.engine.model.SkipReason;

/** 表 delivery_task 一列（07 §10）。 */
public record DeliveryTask(
        String idempotencyKey,
        String enrollmentId,
        String journeyId,
        int journeyVersion,
        String nodeId,
        long visitSeq,
        String templateId,
        String channelId,
        String channelType,
        MessageType messageType,
        DeliveryTaskStatus status,
        SkipReason skipReason,
        String deliveryId,
        Instant attemptedAt,
        Instant createdAt,
        Instant updatedAt) {}
