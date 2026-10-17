package com.cdp.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 表 effect_outbox。insert 由 StateStore.save 在同一交易內呼叫。 */
public interface OutboxStore {

    /** idempotency_key 已存在則略過（重放）。 */
    void insertAll(List<OutboxEntry> entries);

    /** status=PENDING 且 available_at <= now，FOR UPDATE SKIP LOCKED；須在交易內呼叫。 */
    List<OutboxEntry> lockDue(Instant now, int limit);

    void markPublished(String idempotencyKey, Instant publishedAt);

    /** 退回 PENDING 並延後（重試退避，INIT-01 紅線 1）；v0.1.0 Mock 不會用到。 */
    void reschedule(String idempotencyKey, Instant availableAt);

    Optional<OutboxEntry> find(String idempotencyKey);
}
