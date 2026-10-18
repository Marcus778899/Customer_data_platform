package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.journey.EventSourceBinding;
import com.cdp.journey.JourneyDefinition;
import com.cdp.journey.JourneyDefinitionStore;
import com.cdp.journey.JourneyNodeDefinition;
import com.cdp.journey.JourneyStatus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcJourneyDefinitionStore implements JourneyDefinitionStore {

    private static final int MAX_ATTEMPTS = 5;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<JourneyNodeDefinition>> NODES =
            new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcJourneyDefinitionStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public JourneyDefinition insertNewVersion(JourneyDefinition d) {
        String settings = json.writeValueAsString(d.settings());
        String nodes = json.writeValueAsString(d.nodes());
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Integer version =
                        jdbc.sql(
                                        "insert into journey_definition (journey_id, tenant_id, version,"
                                                + " name, description, status, settings, entry_node, nodes,"
                                                + " created_at, published_at)"
                                                + " select cast(:id as text), cast(:tenant as text),"
                                                + " coalesce(max(version), 0) + 1, cast(:name as text),"
                                                + " cast(:description as text), cast(:status as text),"
                                                + " cast(:settings as jsonb), cast(:entry as text),"
                                                + " cast(:nodes as jsonb), :createdAt, :publishedAt"
                                                + " from journey_definition where journey_id = :id"
                                                + " returning version")
                                .param("id", d.journeyId())
                                .param("tenant", d.tenantId())
                                .param("name", d.name())
                                .param("description", d.description())
                                .param("status", d.status().name())
                                .param("settings", settings)
                                .param("entry", d.entryNode())
                                .param("nodes", nodes)
                                .param("createdAt", ts(d.createdAt()))
                                .param("publishedAt", ts(d.publishedAt()))
                                .query(Integer.class)
                                .single();
                return new JourneyDefinition(
                        d.journeyId(),
                        d.tenantId(),
                        version,
                        d.name(),
                        d.description(),
                        d.status(),
                        d.settings(),
                        d.entryNode(),
                        d.nodes(),
                        d.createdAt(),
                        d.publishedAt());
            } catch (DuplicateKeyException e) {
                last = e;
            }
        }
        throw last;
    }

    @Override
    public Optional<JourneyDefinition> find(String tenantId, String journeyId, int version) {
        return jdbc.sql(
                        "select journey_id, tenant_id, version, name, description, status, settings,"
                                + " entry_node, nodes, created_at, published_at from journey_definition"
                                + " where tenant_id = :tenant and journey_id = :id and version = :version")
                .param("tenant", tenantId)
                .param("id", journeyId)
                .param("version", version)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public List<EventSourceBinding> findActiveEventSources(String tenantId, String eventName) {
        return jdbc.sql(
                        "select j.journey_id, j.version, n.node ->> 'id' as node_id"
                                + " from journey_definition j"
                                + " cross join lateral jsonb_array_elements(j.nodes) as n(node)"
                                + " where j.tenant_id = :tenant and j.status = 'ACTIVE'"
                                + " and n.node ->> 'type' = 'EventSourceNode'"
                                + " and n.node -> 'config' ->> 'event_name' = :name"
                                + " and j.version = (select max(m.version) from journey_definition m"
                                + " where m.journey_id = j.journey_id and m.tenant_id = j.tenant_id)"
                                + " order by j.journey_id, node_id")
                .param("tenant", tenantId)
                .param("name", eventName)
                .query(
                        (rs, i) ->
                                new EventSourceBinding(
                                        rs.getString("journey_id"),
                                        rs.getInt("version"),
                                        rs.getString("node_id")))
                .list();
    }

    private JourneyDefinition map(ResultSet rs) throws SQLException {
        return new JourneyDefinition(
                rs.getString("journey_id"),
                rs.getString("tenant_id"),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("description"),
                JourneyStatus.valueOf(rs.getString("status")),
                json.readValue(rs.getString("settings"), MAP),
                rs.getString("entry_node"),
                json.readValue(rs.getString("nodes"), NODES),
                instant(rs, "created_at"),
                instant(rs, "published_at"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
