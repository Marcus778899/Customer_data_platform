package com.cdp.ingest;

import java.time.Instant;
import java.util.List;

/** 表 event。去重鍵 event_id（01 §7）。 */
public interface EventStore {

    /** 已存在回 false（DUPLICATE），不丟例外。 */
    boolean insertIfAbsent(NormalizedEvent event);

    /**
     * 可派發的事件（未派發且 next_dispatch_at 為空或 &lt;= now），依 received_at，FOR UPDATE SKIP LOCKED；須在交易內呼叫。
     * 單列解析失敗不丟例外，以 parseError 回報。
     */
    List<UndispatchedEvent> lockUndispatched(int limit, Instant now);

    /** outcome：DISPATCHED／LATE／EXHAUSTED。 */
    void markDispatched(String eventId, Instant dispatchedAt, String outcome);

    /** dispatch_attempts + 1、寫 last_dispatch_error（截斷 1000 字），回傳新的次數。 */
    int recordFailure(String eventId, String error);

    /** 設定下次可派發時間。 */
    void scheduleRetry(String eventId, Instant nextDispatchAt);
}
