package com.cdp.journey;

import java.time.Instant;

/** journeys.md §1 {@code ReviewBrief}：列表用的審核摘要。 */
public record ReviewBrief(
        String reviewId,
        ReviewStatus status,
        UserRef submittedBy,
        Instant submittedAt,
        UserRef decidedBy,
        Instant decidedAt,
        String reason) {}
