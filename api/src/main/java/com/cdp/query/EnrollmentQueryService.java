package com.cdp.query;

import java.time.Clock;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.cdp.engine.model.EnrollmentStatus;

/** 契約 §4 的分頁、預設值與 404 規則。 */
@Service
public class EnrollmentQueryService {

    private final EnrollmentQueryStore store;
    private final Clock clock;

    public EnrollmentQueryService(EnrollmentQueryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Page<EnrollmentSummary> list(
            String tenantId, String q, Set<EnrollmentStatus> statuses, String cursor, Integer limit) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }

    /** 不存在丟 ApiException(ENROLLMENT_NOT_FOUND)。 */
    public EnrollmentDetail detail(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }

    public Page<DeliveryEntry> deliveries(
            String tenantId, String enrollmentId, String cursor, Integer limit) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }
}
