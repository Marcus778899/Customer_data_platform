package com.cdp.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.TriggerSnapshot;

/** 契約 §4.1 EnrollmentDetail。deliveryHistory 為最新 50 筆，更舊的用 deliveryHistoryNextCursor 續查。 */
public record EnrollmentDetail(
        String enrollmentId,
        String journeyId,
        int journeyVersion,
        String journeyName,
        String profileId,
        EnrollmentStatus status,
        String currentNodeId,
        String currentNodeName,
        String currentNodeType,
        Instant enrolledAt,
        Instant updatedAt,
        Instant terminatedAt,
        String terminationReason,
        WakeConditionView wakeCondition,
        Map<String, Object> variables,
        TriggerSnapshot triggerSnapshot,
        List<TraceEntry> trace,
        List<DeliveryEntry> deliveryHistory,
        String deliveryHistoryNextCursor,
        Instant asOf) {}
