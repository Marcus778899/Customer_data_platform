package com.cdp.engine.runtime;

/** 冪等鍵 {enrollmentId}:{nodeId}:{visitSeq}:{effectIndex}（docs/spec/README.md「冪等」）。只有引擎可呼叫。 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    public static String of(String enrollmentId, String nodeId, long visitSeq, int effectIndex) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
