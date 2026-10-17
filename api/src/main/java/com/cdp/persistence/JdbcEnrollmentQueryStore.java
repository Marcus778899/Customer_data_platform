package com.cdp.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.query.DeliveryEntry;
import com.cdp.query.EnrollmentDetail;
import com.cdp.query.EnrollmentQueryStore;
import com.cdp.query.EnrollmentSummary;
import com.cdp.query.PageCursor;

import tools.jackson.databind.json.JsonMapper;

/** 唯讀查詢；名稱由綁定版本的 journey_definition 帶出（契約 §4.2）。 */
@Repository
public class JdbcEnrollmentQueryStore implements EnrollmentQueryStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcEnrollmentQueryStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<EnrollmentSummary> list(
            String tenantId, String q, Set<EnrollmentStatus> statuses, PageCursor after, int limit) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }

    @Override
    public Optional<EnrollmentDetail> detail(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }

    @Override
    public List<DeliveryEntry> deliveries(
            String tenantId, String enrollmentId, PageCursor after, int limit) {
        throw new UnsupportedOperationException("TODO(BE-03)");
    }
}
