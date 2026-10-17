package com.cdp.journey;

/** 旅程狀態（03 §2）。v0.1.0 建立即為 ACTIVE；其餘流程於後續版本。 */
public enum JourneyStatus {
    DRAFT,
    TESTING,
    PENDING_APPROVAL,
    ACTIVE,
    PAUSED,
    ARCHIVED,
    ABORTED
}
