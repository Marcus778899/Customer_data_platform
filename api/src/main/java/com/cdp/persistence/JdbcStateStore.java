package com.cdp.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.OutboxStore;
import com.cdp.engine.DuplicateEnrollmentException;
import com.cdp.engine.StateStore;
import com.cdp.engine.Timer;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.engine.node.Effect;

import tools.jackson.databind.json.JsonMapper;

/** enrollment_record + node_trace；save 內呼叫 Timer 與 OutboxStore（同一交易）。 */
@Repository
public class JdbcStateStore implements StateStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Timer timer;
    private final OutboxStore outbox;

    public JdbcStateStore(JdbcClient jdbc, JsonMapper json, Timer timer, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.timer = timer;
        this.outbox = outbox;
    }

    @Override
    public EnrollmentState create(EnrollmentState state) throws DuplicateEnrollmentException {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public Optional<EnrollmentState> findById(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public List<EnrollmentState> lockDue(Instant now, int limit) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public List<EnrollmentState> findWaitingForEvent(
            String tenantId, String profileId, String eventName) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public Optional<EnrollmentTrace> findOpenTrace(String tenantId, String enrollmentId) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void save(EnrollmentState state, EnrollmentTrace trace, List<Effect> effects) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
