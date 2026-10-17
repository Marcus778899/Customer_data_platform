package com.cdp.query;

import java.time.Instant;

/** 契約 §4.1 WakeCondition；type = TIMER／EVENT／SIGNAL，不適用的欄位為 null。 */
public record WakeConditionView(
        String type, Instant wakeAt, String eventName, Instant timeoutAt, String signalName) {}
