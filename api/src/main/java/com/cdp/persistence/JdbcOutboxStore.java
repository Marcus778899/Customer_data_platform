package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStatus;
import com.cdp.delivery.OutboxStore;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String COLUMNS =
            "idempotency_key, tenant_id, enrollment_id, journey_id, journey_version, node_id,"
                    + " visit_seq, effect_index, effect_type, payload, status, available_at,"
                    + " publish_count, created_at, published_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcOutboxStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertAll(List<OutboxEntry> entries) {
        for (OutboxEntry e : entries) {
            jdbc.sql(
                            "insert into effect_outbox (" + COLUMNS + ") values (:key, :tenant,"
                                    + " :enrollment, :journey, :version, :node, :visitSeq, :effectIndex,"
                                    + " :effectType, cast(:payload as jsonb), :status, :availableAt,"
                                    + " :publishCount, :createdAt, :publishedAt)"
                                    + " on conflict (idempotency_key) do nothing")
                    .param("key", e.idempotencyKey())
                    .param("tenant", e.tenantId())
                    .param("enrollment", e.enrollmentId())
                    .param("journey", e.journeyId())
                    .param("version", e.journeyVersion())
                    .param("node", e.nodeId())
                    .param("visitSeq", e.visitSeq())
                    .param("effectIndex", e.effectIndex())
                    .param("effectType", e.effectType())
                    .param("payload", json.writeValueAsString(e.payload()))
                    .param("status", e.status().name())
                    .param("availableAt", ts(e.availableAt()))
                    .param("publishCount", e.publishCount())
                    .param("createdAt", ts(e.createdAt()))
                    .param("publishedAt", ts(e.publishedAt()))
                    .update();
        }
    }

    @Override
    public List<OutboxEntry> lockDue(Instant now, int limit) {
        return jdbc.sql(
                        "select " + COLUMNS + " from effect_outbox where status = 'PENDING'"
                                + " and available_at <= :now order by available_at, idempotency_key"
                                + " limit :limit for update skip locked")
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public void markPublished(String idempotencyKey, Instant publishedAt) {
        jdbc.sql(
                        "update effect_outbox set status = 'PUBLISHED', published_at = :at,"
                                + " publish_count = publish_count + 1 where idempotency_key = :key")
                .param("at", ts(publishedAt))
                .param("key", idempotencyKey)
                .update();
    }

    @Override
    public void reschedule(String idempotencyKey, Instant availableAt) {
        jdbc.sql(
                        "update effect_outbox set status = 'PENDING', available_at = :at"
                                + " where idempotency_key = :key")
                .param("at", ts(availableAt))
                .param("key", idempotencyKey)
                .update();
    }

    @Override
    public Optional<OutboxEntry> find(String idempotencyKey) {
        return jdbc.sql("select " + COLUMNS + " from effect_outbox where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    private OutboxEntry map(ResultSet rs) throws SQLException {
        return new OutboxEntry(
                rs.getString("idempotency_key"),
                rs.getString("tenant_id"),
                rs.getString("enrollment_id"),
                rs.getString("journey_id"),
                rs.getInt("journey_version"),
                rs.getString("node_id"),
                rs.getLong("visit_seq"),
                rs.getInt("effect_index"),
                rs.getString("effect_type"),
                json.readValue(rs.getString("payload"), MAP),
                OutboxStatus.valueOf(rs.getString("status")),
                instant(rs, "available_at"),
                rs.getInt("publish_count"),
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
