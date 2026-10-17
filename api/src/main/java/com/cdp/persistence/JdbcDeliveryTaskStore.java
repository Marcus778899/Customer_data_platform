package com.cdp.persistence;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.DeliveryTask;
import com.cdp.delivery.DeliveryTaskStore;
import com.cdp.engine.model.DeliveryRecord;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcDeliveryTaskStore implements DeliveryTaskStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcDeliveryTaskStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public DeliveryTask insertIfAbsentAndLock(DeliveryTask task) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }

    @Override
    public void update(DeliveryTask task) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }

    @Override
    public Optional<DeliveryTask> find(String idempotencyKey) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }

    @Override
    public void appendDeliveryHistory(String tenantId, String enrollmentId, DeliveryRecord record) {
        throw new UnsupportedOperationException("TODO(BE-02)");
    }
}
