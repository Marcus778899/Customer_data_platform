package com.cdp.engine.runtime;

/** 觸發快照截斷到最後一步仍超過 64 KB（spec 02 §5 步驟 3）：拒絕建立 Enrollment。 */
public class SnapshotTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SnapshotTooLargeException(String message) {
        super(message);
    }
}
