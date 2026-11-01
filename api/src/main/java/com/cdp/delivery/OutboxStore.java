package com.cdp.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 表 effect_outbox。insert 由 StateStore.save 在同一交易內呼叫。relay 的 lease 與 fencing 見 spec 07 §12「relay」：
 * available_at 兼作 lease 與 fencing 值，回寫只在 {@code status='PENDING' AND available_at = leaseUntil} 時生效。
 */
public interface OutboxStore {

    /** 回寫用的 fencing 值：搶列時取得的 lease_until。 */
    record Lease(String idempotencyKey, Instant leaseUntil) {}

    /** idempotency_key 已存在則略過（重放）。 */
    void insertAll(List<OutboxEntry> entries);

    /**
     * 搶列（單一敘述，不需外層交易）：status=PENDING 且 available_at <= now，FOR UPDATE SKIP LOCKED，同時把
     * available_at 設為 leaseUntil 並回傳列（回傳列的 availableAt 即 fencing 值）。
     */
    List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit);

    /** 標 PUBLISHED、publish_count+1；僅在 status=PENDING 且 available_at=leaseUntil 時生效，回傳是否生效。 */
    boolean markPublished(String idempotencyKey, Instant leaseUntil, Instant publishedAt);

    /** 退回 PENDING 並延後到 availableAt；同樣的條件式回寫，回傳是否生效。 */
    boolean reschedule(String idempotencyKey, Instant leaseUntil, Instant availableAt);

    /** {@link #markPublished} 的批次版（一次 batchUpdate），回傳與輸入同序的各列影響筆數。 */
    int[] markPublishedAll(List<Lease> leases, Instant publishedAt);

    /** {@link #reschedule} 的批次版，回傳與輸入同序的各列影響筆數。 */
    int[] rescheduleAll(List<Lease> leases, Instant availableAt);

    /** PENDING → CANCELLED（不論是否被 relay 搶走）；回傳取消筆數。 */
    int cancelPendingByEnrollment(String enrollmentId);

    int cancelPendingByJourney(String journeyId);

    Optional<OutboxEntry> find(String idempotencyKey);
}
