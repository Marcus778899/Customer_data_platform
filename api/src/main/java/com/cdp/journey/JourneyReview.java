package com.cdp.journey;

import java.time.Instant;

/** journeys.md §1 {@code Review}（表 journey_review）。管理金鑰建立的紀錄 revision、submittedBy、decidedBy 皆為 null。 */
public record JourneyReview(
        String reviewId,
        String journeyId,
        String action,
        ReviewStatus status,
        Integer revision,
        Integer baseVersion,
        String note,
        UserRef submittedBy,
        Instant submittedAt,
        UserRef decidedBy,
        Instant decidedAt,
        String reason,
        Integer version) {

    public static final String PUBLISH = "PUBLISH";

    public ReviewBrief brief() {
        return new ReviewBrief(reviewId, status, submittedBy, submittedAt, decidedBy, decidedAt, reason);
    }
}
