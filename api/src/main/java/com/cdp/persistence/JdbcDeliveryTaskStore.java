package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.DeliveryTask;
import com.cdp.delivery.DeliveryTaskStatus;
import com.cdp.delivery.DeliveryTaskStore;
import com.cdp.engine.model.DeliveryRecord;
import com.cdp.engine.model.MessageType;
import com.cdp.engine.model.SkipReason;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcDeliveryTaskStore implements DeliveryTaskStore {

    private static final String COLUMNS =
            "idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                    + " visit_seq, template_id, channel_id, channel_type, message_type, status,"
                    + " skip_reason, delivery_id, attempted_at, created_at, updated_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcDeliveryTaskStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public DeliveryTask insertIfAbsentAndLock(DeliveryTask t) {
        jdbc.sql(
                        "insert into delivery_task (" + COLUMNS + ") values (:key,"
                                + " :enrollment, :journey, :version, :node, :visitSeq, :template,"
                                + " :channel, :channelType, :messageType, :status, :skipReason,"
                                + " :deliveryId, :attemptedAt, :createdAt, :updatedAt)"
                                + " on conflict (idempotency_key) do nothing")
                .param("key", t.idempotencyKey())
                .param("enrollment", t.enrollmentId())
                .param("journey", t.journeyId())
                .param("version", t.journeyVersion())
                .param("node", t.nodeId())
                .param("visitSeq", t.visitSeq())
                .param("template", t.templateId())
                .param("channel", t.channelId())
                .param("channelType", t.channelType())
                .param("messageType", t.messageType() == null ? null : t.messageType().name(), java.sql.Types.VARCHAR)
                .param("status", t.status().name())
                .param("skipReason", t.skipReason() == null ? null : t.skipReason().name(), java.sql.Types.VARCHAR)
                .param("deliveryId", t.deliveryId(), java.sql.Types.VARCHAR)
                .param("attemptedAt", ts(t.attemptedAt()))
                .param("createdAt", ts(t.createdAt()))
                .param("updatedAt", ts(t.updatedAt()))
                .update();
        return jdbc.sql(
                        "select " + COLUMNS + " from delivery_task where idempotency_key = :key"
                                + " for update")
                .param("key", t.idempotencyKey())
                .query((rs, n) -> map(rs))
                .single();
    }

    @Override
    public void update(DeliveryTask t) {
        jdbc.sql(
                        "update delivery_task set template_id = :template, channel_id = :channel,"
                                + " channel_type = :channelType, message_type = :messageType,"
                                + " status = :status, skip_reason = :skipReason,"
                                + " delivery_id = :deliveryId, attempted_at = :attemptedAt,"
                                + " updated_at = :updatedAt where idempotency_key = :key")
                .param("key", t.idempotencyKey())
                .param("template", t.templateId())
                .param("channel", t.channelId())
                .param("channelType", t.channelType())
                .param("messageType", t.messageType() == null ? null : t.messageType().name(), java.sql.Types.VARCHAR)
                .param("status", t.status().name())
                .param("skipReason", t.skipReason() == null ? null : t.skipReason().name(), java.sql.Types.VARCHAR)
                .param("deliveryId", t.deliveryId(), java.sql.Types.VARCHAR)
                .param("attemptedAt", ts(t.attemptedAt()))
                .param("updatedAt", ts(t.updatedAt()))
                .update();
    }

    @Override
    public Optional<DeliveryTask> find(String idempotencyKey) {
        return jdbc.sql("select " + COLUMNS + " from delivery_task where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public void appendDeliveryHistory(String enrollmentId, DeliveryRecord record) {
        jdbc.sql(
                        "update enrollment_record set delivery_history = coalesce(("
                                + " select jsonb_agg(s.e order by s.i) from ("
                                + " select e, i from jsonb_array_elements(delivery_history"
                                + " || jsonb_build_array(cast(:record as jsonb)))"
                                + " with ordinality as t(e, i) order by i desc limit 50) s),"
                                + " '[]'::jsonb)"
                                + " where enrollment_id = :enrollment")
                .param("record", json.writeValueAsString(record))
                .param("enrollment", enrollmentId)
                .update();
    }

    private static DeliveryTask map(ResultSet rs) throws SQLException {
        String messageType = rs.getString("message_type");
        String skipReason = rs.getString("skip_reason");
        return new DeliveryTask(
                rs.getString("idempotency_key"),
                rs.getString("enrollment_id"),
                rs.getString("journey_id"),
                rs.getInt("journey_version"),
                rs.getString("node_id"),
                rs.getLong("visit_seq"),
                rs.getString("template_id"),
                rs.getString("channel_id"),
                rs.getString("channel_type"),
                messageType == null ? null : MessageType.valueOf(messageType),
                DeliveryTaskStatus.valueOf(rs.getString("status")),
                skipReason == null ? null : SkipReason.valueOf(skipReason),
                rs.getString("delivery_id"),
                instant(rs, "attempted_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
