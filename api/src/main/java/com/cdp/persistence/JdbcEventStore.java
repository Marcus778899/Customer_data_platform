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
import com.cdp.ingest.UndispatchedEvent;

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
                                "insert into event (event_id, source_type, source_id, name,"
                                        + " occurred_at, received_at, identity, properties, context)"
                                        + " values (:eventId, :sourceType, :sourceId, :name,"
                                        + " :occurredAt, :receivedAt, cast(:identity as jsonb),"
                                        + " cast(:properties as jsonb), cast(:context as jsonb))"
                                        + " on conflict (event_id) do nothing")
                        .param("eventId", event.eventId())
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
    public List<UndispatchedEvent> lockUndispatched(int limit, Instant now) {
        return jdbc.sql(
                        "select event_id, source_type, source_id, name, occurred_at,"
                                + " received_at, identity, properties, context from event"
                                + " where dispatched_at is null"
                                + " and (next_dispatch_at is null or next_dispatch_at <= :now)"
                                + " order by received_at, event_id"
                                + " limit :limit for update skip locked")
                .param("now", ts(now))
                .param("limit", limit)
                .query(this::mapSafely)
                .list();
    }

    @Override
    public void markDispatched(
            String eventId, Instant dispatchedAt, String outcome) {
        jdbc.sql(
                        "update event set dispatched_at = :at, dispatch_outcome = :outcome"
                                + " where event_id = :id")
                .param("at", ts(dispatchedAt))
                .param("outcome", outcome)
                .param("id", eventId)
                .update();
    }

    @Override
    public int recordFailure(String eventId, String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        return jdbc.sql(
                        "update event set dispatch_attempts = dispatch_attempts + 1,"
                                + " last_dispatch_error = :error"
                                + " where event_id = :id"
                                + " returning dispatch_attempts")
                .param("error", trimmed)
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void scheduleRetry(String eventId, Instant nextDispatchAt) {
        jdbc.sql(
                        "update event set next_dispatch_at = :at"
                                + " where event_id = :id")
                .param("at", ts(nextDispatchAt))
                .param("id", eventId)
                .update();
    }

    private UndispatchedEvent mapSafely(ResultSet rs, int n) throws SQLException {
        String id = rs.getString("event_id");
        try {
            return new UndispatchedEvent(id, map(rs), null);
        } catch (RuntimeException e) {
            return new UndispatchedEvent(
                    id, null, e.getClass().getSimpleName() + ": unparsable event row");
        }
    }

    private NormalizedEvent map(ResultSet rs) throws SQLException {
        String properties = rs.getString("properties");
        String context = rs.getString("context");
        return new NormalizedEvent(
                rs.getString("event_id"),
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
