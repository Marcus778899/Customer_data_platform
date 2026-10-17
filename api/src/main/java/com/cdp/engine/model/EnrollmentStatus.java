package com.cdp.engine.model;

/** Enrollment 狀態機（docs/spec/02-enrollment-model.md §3）。 */
public enum EnrollmentStatus {
    PENDING(false),
    ACTIVE(false),
    WAITING(false),
    SUSPENDED(false),
    COMPLETED(true),
    GOAL_MET(true),
    EXITED(true),
    FAILED(true),
    ABORTED(true);

    private final boolean terminal;

    EnrollmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 終態不可再轉移（02 §3 轉移規則）。 */
    public boolean isTerminal() {
        return terminal;
    }
}
