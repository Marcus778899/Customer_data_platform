package com.cdp.delivery;

/** 訊息被取消的原因（journey-engine.md §2 CancelReason）。 */
public enum CancelReason {
    /** 旅程停止（journeys.md §3.5）。 */
    JOURNEY_STOPPED,
    /** goal／global_exit 使旅程進度中途結束（spec 02 §3）。 */
    ENROLLMENT_ENDED
}
