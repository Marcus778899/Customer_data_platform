package com.cdp.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.engine.node.Effect;

/** 狀態儲存（04 §7）。save 為單一交易：狀態 + 軌跡 + outbox + next_wake_at（INIT-01 R1）。 */
public interface StateStore {

    EnrollmentState create(EnrollmentState state) throws DuplicateEnrollmentException;

    Optional<EnrollmentState> findById(String tenantId, String enrollmentId);

    /** 跨租戶取到期 WAITING 列（FOR UPDATE SKIP LOCKED）；now 一律來自注入的 Clock。 */
    List<EnrollmentState> lockDue(Instant now, int limit);

    List<EnrollmentState> findWaitingForEvent(String tenantId, String profileId, String eventName);

    /** 尚未離開的軌跡列；等待節點被喚醒時沿用它的 seq。 */
    Optional<EnrollmentTrace> findOpenTrace(String tenantId, String enrollmentId);

    /** effects 的 idempotencyKey 須已由引擎蓋章；不寫 delivery_history 欄位（由 consumer 追加）。 */
    void save(EnrollmentState state, EnrollmentTrace trace, List<Effect> effects);
}
