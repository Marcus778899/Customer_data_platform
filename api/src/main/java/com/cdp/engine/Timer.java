package com.cdp.engine;

import java.time.Instant;

/** 計時器抽象（04 §7）。自建實作只寫 next_wake_at，須在 StateStore.save 的同一交易內呼叫（R1）。 */
public interface Timer {

    void schedule(String enrollmentId, Instant wakeAt);

    void cancel(String enrollmentId);

    void cancelAll(String journeyId);
}
