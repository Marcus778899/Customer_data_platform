package com.cdp.engine.model;

import java.time.Instant;
import java.util.Map;

/**
 * 節點軌跡（02 §8）。等待節點一列：進入時 exitedAt／outPort 為 null，喚醒離開時補上一次（07 §5）。
 */
public record EnrollmentTrace(
        String enrollmentId,
        long seq,
        String nodeId,
        String nodeType,
        Instant enteredAt,
        Instant exitedAt,
        String outPort,
        String decisionReason,
        Map<String, Object> evaluated) {}
