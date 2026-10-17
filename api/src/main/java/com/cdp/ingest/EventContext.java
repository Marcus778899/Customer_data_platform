package com.cdp.ingest;

/** 01 §3 context。 */
public record EventContext(
        String actorId,
        String actorRole,
        String trustLevel,
        String ip,
        String userAgent,
        String requestId) {}
