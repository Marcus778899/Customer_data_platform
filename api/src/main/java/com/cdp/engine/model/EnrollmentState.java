package com.cdp.engine.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 契約層 Enrollment（02 §2）。不得引用 EnrollmentRecord 或 next_wake_at（ArchitectureTest）。 */
public record EnrollmentState(
        String enrollmentId,
        String tenantId,
        String journeyId,
        int journeyVersion,
        String profileId,
        EnrollmentStatus status,
        String currentNodeId,
        WakeCondition wakeCondition,
        TriggerSnapshot triggerSnapshot,
        String dedupKey,
        Map<String, Object> variables,
        List<DeliveryRecord> deliveryHistory,
        Instant enrolledAt,
        Instant updatedAt,
        Instant terminatedAt,
        String terminationReason) {}
