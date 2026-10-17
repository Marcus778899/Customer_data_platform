package com.cdp.query;

import java.time.Instant;

import com.cdp.engine.model.EnrollmentStatus;

/** 契約 §4.1 EnrollmentSummary。 */
public record EnrollmentSummary(
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
        Instant terminatedAt) {}
