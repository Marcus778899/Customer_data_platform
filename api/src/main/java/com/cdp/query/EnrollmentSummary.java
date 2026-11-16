package com.cdp.query;

import java.time.Instant;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.profile.IdentifierView;

/** 契約 §4.1 EnrollmentSummary。profileName、profileIdentifier 由 Store 帶原值，service 依 §4.4 遮罩後才回應。 */
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
        Instant terminatedAt,
        String profileName,
        IdentifierView profileIdentifier) {}
