package com.cdp.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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
            "idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                    + " visit_seq, effect_index, effect_type, payload, status, available_at,"
                    + " publish_count, created_at, published_at";

    /** relay 搶列 SQL（具名參數 :lease、:now、:limit）；public 供驗收測試 EXPLAIN。 */
    public static final String CLAIM_DUE_SQL =
            "update effect_outbox set available_at = :lease where idempotency_key in ("
                    + " select idempotency_key from effect_outbox where status = 'PENDING'"
                    + " and available_at <= :now order by available_at, idempotency_key"
                    + " limit :limit for update skip locked) returning " + COLUMNS;

    /** 條件式回寫（fencing）：status 仍為 PENDING 且 available_at 仍是搶列時的 lease。參數：at、key、lease。 */
    private static final String MARK_PUBLISHED =
            "update effect_outbox set status = 'PUBLISHED', published_at = ?,"
                    + " publish_count = publish_count + 1"
                    + " where idempotency_key = ? and status = 'PENDING' and available_at = ?";

    private static final String RESCHEDULE =
            "update effect_outbox set available_at = ?"
                    + " where idempotency_key = ? and status = 'PENDING' and available_at = ?";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper json;

    public JdbcOutboxStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    @Override
    public void insertAll(List<OutboxEntry> entries) {
        for (OutboxEntry e : entries) {
            jdbc.sql(
                            "insert into effect_outbox (" + COLUMNS + ") values (:key,"
                                    + " :enrollment, :journey, :version, :node, :visitSeq, :effectIndex,"
                                    + " :effectType, cast(:payload as jsonb), :status, :availableAt,"
                                    + " :publishCount, :createdAt, :publishedAt)"
                                    + " on conflict (idempotency_key) do nothing")
                    .param("key", e.idempotencyKey())
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
    public List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql(CLAIM_DUE_SQL)
                .param("lease", ts(leaseUntil))
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public boolean markPublished(String idempotencyKey, Instant leaseUntil, Instant publishedAt) {
        return jdbcTemplate.update(
                        MARK_PUBLISHED, ts(publishedAt), idempotencyKey, ts(leaseUntil))
                == 1;
    }

    @Override
    public boolean reschedule(String idempotencyKey, Instant leaseUntil, Instant availableAt) {
        return jdbcTemplate.update(RESCHEDULE, ts(availableAt), idempotencyKey, ts(leaseUntil))
                == 1;
    }

    @Override
    public int[] markPublishedAll(List<Lease> leases, Instant publishedAt) {
        return batch(MARK_PUBLISHED, leases, publishedAt);
    }

    @Override
    public int[] rescheduleAll(List<Lease> leases, Instant availableAt) {
        return batch(RESCHEDULE, leases, availableAt);
    }

    private int[] batch(String sql, List<Lease> leases, Instant at) {
        if (leases.isEmpty()) {
            return new int[0];
        }
        OffsetDateTime atValue = ts(at);
        return jdbcTemplate.batchUpdate(
                sql,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Lease lease = leases.get(i);
                        ps.setObject(1, atValue);
                        ps.setString(2, lease.idempotencyKey());
                        ps.setObject(3, ts(lease.leaseUntil()));
                    }

                    @Override
                    public int getBatchSize() {
                        return leases.size();
                    }
                });
    }

    @Override
    public int cancelPendingByEnrollment(String enrollmentId) {
        return jdbc.sql(
                        "update effect_outbox set status = 'CANCELLED'"
                                + " where enrollment_id = :id and status = 'PENDING'")
                .param("id", enrollmentId)
                .update();
    }

    @Override
    public int cancelPendingByJourney(String journeyId) {
        return jdbc.sql(
                        "update effect_outbox set status = 'CANCELLED'"
                                + " where journey_id = :id and status = 'PENDING'")
                .param("id", journeyId)
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
