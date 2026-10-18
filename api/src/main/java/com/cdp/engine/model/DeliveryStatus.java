package com.cdp.engine.model;

/** deliveryHistory 快取的結果（02 §7）。任務完整狀態見 com.cdp.delivery.DeliveryTaskStatus。 */
public enum DeliveryStatus {
    SENT,
    SKIPPED,
    FAILED
}
