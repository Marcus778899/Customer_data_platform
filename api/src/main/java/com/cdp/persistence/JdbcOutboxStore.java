package com.cdp.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStore;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcOutboxStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertAll(List<OutboxEntry> entries) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public List<OutboxEntry> lockDue(Instant now, int limit) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void markPublished(String idempotencyKey, Instant publishedAt) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void reschedule(String idempotencyKey, Instant availableAt) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public Optional<OutboxEntry> find(String idempotencyKey) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
