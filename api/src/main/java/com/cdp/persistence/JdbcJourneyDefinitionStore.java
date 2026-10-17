package com.cdp.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcJourneyDefinitionStore implements JourneyDefinitionStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcJourneyDefinitionStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public JourneyDefinition insertNewVersion(JourneyDefinition definition) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public Optional<JourneyDefinition> find(String tenantId, String journeyId, int version) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }

    @Override
    public List<EventSourceBinding> findActiveEventSources(String tenantId, String eventName) {
        throw new UnsupportedOperationException("TODO(BE-01)");
    }
}
