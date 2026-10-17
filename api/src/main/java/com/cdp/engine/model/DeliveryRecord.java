package com.cdp.engine.model;

import java.time.Instant;

/**
 * G4 發送歷史的一筆（02 §7）。traceSeq = 產生它的 EnrollmentTrace.seq（冪等鍵的 visitSeq）。 v0.1.0
 * 無模板表，messageType 為 null。
 */
public record DeliveryRecord(
        String nodeId,
        long traceSeq,
        String templateId,
        String channelType,
        String channelId,
        MessageType messageType,
        DeliveryStatus status,
        SkipReason skipReason,
        Instant attemptedAt,
        String deliveryId) {}
