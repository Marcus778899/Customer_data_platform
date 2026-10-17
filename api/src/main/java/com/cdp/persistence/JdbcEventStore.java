package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.ingest.EventContext;
import com.cdp.ingest.EventIdentity;
import com.cdp.ingest.EventStore;
import com.cdp.ingest.NormalizedEvent;
import com.cdp.ingest.SourceType;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcEventStore implements EventStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcEventStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insertIfAbsent(NormalizedEvent event) {
        int rows =
                jdbc.sql(
                                "insert into event (event_id, tenant_id, source_type, source_id, name,"
                                        + " occurred_at, received_at, identity, properties, context)"
                                        + " values (:eventId, :tenant, :sourceType, :sourceId, :name,"
                                        + " :occurredAt, :receivedAt, cast(:identity as jsonb),"
                                        + " cast(:properties as jsonb), cast(:context as jsonb))"
                                        + " on conflict (tenant_id, event_id) do nothing")
                        .param("eventId", event.eventId())
                        .param("tenant", event.tenantId())
                        .param("sourceType", event.sourceType().name())
                        .param("sourceId", event.sourceId())
                        .param("name", event.name())
                        .param("occurredAt", ts(event.occurredAt()))
                        .param("receivedAt", ts(event.receivedAt()))
                        .param("identity", json.writeValueAsString(event.identity()))
                        .param("properties", event.properties() == null ? null : json.writeValueAsString(event.properties()))
                        .param("context", event.context() == null ? null : json.writeValueAsString(event.context()))
                        .update();
        return rows == 1;
    }

    @Override
    public List<NormalizedEvent> lockUndispatched(int limit) {
        return jdbc.sql(
                        "select event_id, tenant_id, source_type, source_id, name, occurred_at,"
                                + " received_at, identity, properties, context from event"
                                + " where dispatched_at is null order by received_at, event_id"
                                + " limit :limit for update skip locked")
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public void markDispatched(String tenantId, String eventId, Instant dispatchedAt) {
        jdbc.sql(
                        "update event set dispatched_at = :at"
                                + " where tenant_id = :tenant and event_id = :id")
                .param("at", ts(dispatchedAt))
                .param("tenant", tenantId)
                .param("id", eventId)
                .update();
    }

    private NormalizedEvent map(ResultSet rs) throws SQLException {
        String properties = rs.getString("properties");
        String context = rs.getString("context");
        return new NormalizedEvent(
                rs.getString("event_id"),
                rs.getString("tenant_id"),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_id"),
                rs.getString("name"),
                instant(rs, "occurred_at"),
                instant(rs, "received_at"),
                json.readValue(rs.getString("identity"), EventIdentity.class),
                properties == null ? null : json.readValue(properties, MAP),
                context == null ? null : json.readValue(context, EventContext.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
