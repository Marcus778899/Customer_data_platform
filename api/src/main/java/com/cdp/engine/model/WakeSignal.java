package com.cdp.engine.model;

import java.time.Instant;
import java.util.Map;

/** 喚醒資訊（docs/spec/04-node-contract.md §3）。 */
public sealed interface WakeSignal {

    record TimerFired(Instant firedAt) implements WakeSignal {}

    record EventArrived(String eventName, Map<String, Object> properties, Instant occurredAt)
            implements WakeSignal {}

    record Timeout(Instant timedOutAt) implements WakeSignal {}
}
