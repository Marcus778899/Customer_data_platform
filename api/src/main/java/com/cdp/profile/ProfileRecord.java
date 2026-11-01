package com.cdp.profile;

import java.time.Instant;
import java.util.Map;

/** 讀自 {@code profile} 表的一列（屬性未遮罩，只在後端內部流動）。 */
public record ProfileRecord(
        String profileId, Map<String, Object> attributes, Instant createdAt, Instant updatedAt) {}
