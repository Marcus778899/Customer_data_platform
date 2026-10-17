package com.cdp.engine.model;

import java.time.Instant;
import java.util.Map;

/** 「在等什麼」（02 §4）。契約層只表達語意，不含 next_wake_at。 */
public sealed interface WakeCondition {

    /** 等一段時間／等到某時刻。 */
    record Timer(Instant wakeAt) implements WakeCondition {}

    /** 等事件，逾時走另一條路；timeoutAt 為 null 表示不逾時（v0.2.0 起使用）。 */
    record Event(String eventName, Map<String, Object> filter, Instant timeoutAt)
            implements WakeCondition {}

    /** 等人工或外部訊號，無逾時（v0.2.0 起使用）。 */
    record Signal(String signalName) implements WakeCondition {}
}
