package com.cdp.query;

import java.time.Instant;
import java.util.Map;

/** 契約 §4.1 TraceEntry。 */
public record TraceEntry(
        long seq,
        String nodeId,
        String nodeType,
        String nodeName,
        Instant enteredAt,
        Instant exitedAt,
        String outPort,
        String decisionReason,
        Map<String, Object> evaluated) {}
