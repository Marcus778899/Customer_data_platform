package com.cdp.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cdp.engine.model.EnrollmentStatus;

/** 唯讀查詢。after 為 null 表示第一頁；回傳最多 limit + 1 筆供判斷下一頁。 */
public interface EnrollmentQueryStore {

    /** q 精確比對 profile_id 或 enrollment_id；statuses 為空表示不篩。新到舊。 */
    List<EnrollmentSummary> list(
            String tenantId, String q, Set<EnrollmentStatus> statuses, PageCursor after, int limit);

    /** deliveryHistory 留空、asOf 為 null，由 service 補。 */
    Optional<EnrollmentDetail> detail(String tenantId, String enrollmentId);

    List<DeliveryEntry> deliveries(String tenantId, String enrollmentId, PageCursor after, int limit);
}
