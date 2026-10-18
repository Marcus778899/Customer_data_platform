package com.cdp.engine.model;

/** 被略過的原因（02 §7）。 */
public enum SkipReason {
    CONSENT,
    FREQUENCY_CAP,
    QUIET_HOURS,
    PRIORITY_LOST,
    BUDGET_EXCEEDED,
    KILL_SWITCH
}
