package com.cdp.profile;

import java.time.Instant;
import java.util.List;

/** profiles.md §1 {@code ProfileSummary}。 */
public record ProfileSummary(
        String profileId,
        MatchedBy matchedBy,
        List<IdentifierView> identifiers,
        Instant createdAt,
        Instant updatedAt) {}
