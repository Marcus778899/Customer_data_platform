package com.cdp.query;

import java.time.Instant;

import com.cdp.delivery.DeliveryTaskStatus;
import com.cdp.engine.model.MessageType;
import com.cdp.engine.model.SkipReason;

/** 契約 §4.1 Delivery（來源 delivery_task）。 */
public record DeliveryEntry(
        String idempotencyKey,
        String nodeId,
        String nodeName,
        long traceSeq,
        String templateId,
        String channelId,
        String channelType,
        MessageType messageType,
        DeliveryTaskStatus status,
        SkipReason skipReason,
        Instant attemptedAt,
        String deliveryId) {}
