package com.cdp.delivery;

/** 任務級發送狀態（表 delivery_task；契約 §4 delivery.status）。 */
public enum DeliveryTaskStatus {
    PENDING,
    SENT,
    SKIPPED,
    FAILED,
    CANCELLED
}
