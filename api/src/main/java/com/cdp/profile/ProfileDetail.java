package com.cdp.profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** profiles.md §1 {@code ProfileDetail}（已遮罩）。 */
public record ProfileDetail(
        String profileId,
        List<IdentifierView> identifiers,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> attributes,
        List<String> maskedPaths,
        Instant asOf) {}
