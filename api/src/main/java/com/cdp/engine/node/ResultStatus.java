package com.cdp.engine.node;

/** 節點結果（04 §4）。COMPLETE = 走到 EndNode，Enrollment 轉 COMPLETED。 */
public enum ResultStatus {
    ADVANCE,
    WAIT,
    COMPLETE,
    EXIT,
    GOAL,
    FAIL
}
