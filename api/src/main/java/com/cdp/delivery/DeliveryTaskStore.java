package com.cdp.delivery;

import java.util.Optional;

import com.cdp.engine.model.DeliveryRecord;

/** 表 delivery_task 與 enrollment_record.delivery_history 的追加。皆須在 consumer 的交易內呼叫。 */
public interface DeliveryTaskStore {

    /** INSERT … ON CONFLICT DO NOTHING 後 SELECT … FOR UPDATE，回傳現況。 */
    DeliveryTask insertIfAbsentAndLock(DeliveryTask task);

    void update(DeliveryTask task);

    Optional<DeliveryTask> find(String idempotencyKey);

    /** 單一 UPDATE 追加並只保留最近 50 筆（02 §7），不與引擎的 save 互相覆蓋。 */
    void appendDeliveryHistory(String tenantId, String enrollmentId, DeliveryRecord record);
}
