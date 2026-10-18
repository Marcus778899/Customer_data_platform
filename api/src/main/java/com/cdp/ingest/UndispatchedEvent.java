package com.cdp.ingest;

/** 取出的未派發列；解析失敗時 event 為 null，parseError 為摘要（該列直接走 EXHAUSTED，不卡整批）。 */
public record UndispatchedEvent(
        String tenantId, String eventId, NormalizedEvent event, String parseError) {}
