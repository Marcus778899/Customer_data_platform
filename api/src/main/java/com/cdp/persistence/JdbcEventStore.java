package com.cdp.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.ingest.EventStore;
import com.cdp.ingest.NormalizedEvent;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcEventStore implements EventStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcEventStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insertIfAbsent(NormalizedEvent event) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public List<NormalizedEvent> lockUndispatched(int limit) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public void markDispatched(String tenantId, String eventId, Instant dispatchedAt) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
