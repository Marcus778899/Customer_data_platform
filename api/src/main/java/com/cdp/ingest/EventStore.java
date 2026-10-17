package com.cdp.ingest;

import java.time.Instant;
import java.util.List;

/** 表 event。去重鍵 (tenant_id, event_id)（01 §7）。 */
public interface EventStore {

    /** 已存在回 false（DUPLICATE），不丟例外。 */
    boolean insertIfAbsent(NormalizedEvent event);

    /** 未派發事件，依 received_at，FOR UPDATE SKIP LOCKED；須在交易內呼叫。 */
    List<NormalizedEvent> lockUndispatched(int limit);

    void markDispatched(String tenantId, String eventId, Instant dispatchedAt);
}
