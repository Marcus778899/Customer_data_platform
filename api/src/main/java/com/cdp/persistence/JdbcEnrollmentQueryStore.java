package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.delivery.DeliveryTaskStatus;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.MessageType;
import com.cdp.engine.model.SkipReason;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.query.DeliveryEntry;
import com.cdp.query.EnrollmentDetail;
import com.cdp.query.EnrollmentQueryStore;
import com.cdp.query.EnrollmentSummary;
import com.cdp.query.PageCursor;
import com.cdp.query.TraceEntry;
import com.cdp.query.WakeConditionView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 唯讀查詢；名稱由綁定版本的 journey_definition 帶出（契約 §4.2）。 */
@Repository
public class JdbcEnrollmentQueryStore implements EnrollmentQueryStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final String SUMMARY_SELECT =
            "select e.enrollment_id, e.journey_id, e.journey_version, coalesce(jd.name, '') as journey_name,"
                    + " e.profile_id, e.status, e.current_node_id, n.name as node_name, n.type as node_type,"
                    + " e.enrolled_at, e.updated_at, e.terminated_at";

    /** 目前節點的名稱與型別以 lateral 從綁定版本的 nodes 取出。 */
    private static final String SUMMARY_FROM =
            " from enrollment_record e"
                    + " left join journey_definition jd on jd.tenant_id = e.tenant_id"
                    + " and jd.journey_id = e.journey_id and jd.version = e.journey_version"
                    + " left join lateral (select x ->> 'name' as name, x ->> 'type' as type"
                    + " from jsonb_array_elements(jd.nodes) x where x ->> 'id' = e.current_node_id"
                    + " limit 1) n on true";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcEnrollmentQueryStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<EnrollmentSummary> list(
            String tenantId, String q, Set<EnrollmentStatus> statuses, PageCursor after, int limit) {
        boolean filtered = statuses != null && !statuses.isEmpty();
        StringBuilder sql =
                new StringBuilder(SUMMARY_SELECT)
                        .append(SUMMARY_FROM)
                        .append(" where e.tenant_id = :tenant and (e.profile_id = :q or e.enrollment_id = :q)");
        if (filtered) {
            sql.append(" and e.status in (:statuses)");
        }
        if (after != null) {
            sql.append(" and (e.enrolled_at, e.enrollment_id) < (:at, :id)");
        }
        sql.append(" order by e.enrolled_at desc, e.enrollment_id desc limit :limit");

        JdbcClient.StatementSpec spec =
                jdbc.sql(sql.toString()).param("tenant", tenantId).param("q", q).param("limit", limit);
        if (filtered) {
            spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (after != null) {
            spec = spec.param("at", ts(after.at())).param("id", after.id());
        }
        return spec.query((rs, n) -> summary(rs)).list();
    }

    @Override
    public Optional<EnrollmentDetail> detail(String tenantId, String enrollmentId) {
        return jdbc.sql(
                        SUMMARY_SELECT
                                + ", e.termination_reason, e.wake_type, e.next_wake_at, e.wake_event_name,"
                                + " e.variables, e.trigger_snapshot, jd.nodes as nodes"
                                + SUMMARY_FROM
                                + " where e.tenant_id = :tenant and e.enrollment_id = :id")
                .param("tenant", tenantId)
                .param("id", enrollmentId)
                .query(
                        (rs, n) -> {
                            EnrollmentSummary s = summary(rs);
                            Map<String, String> names = nodeNames(rs.getString("nodes"));
                            return new EnrollmentDetail(
                                    s.enrollmentId(),
                                    s.journeyId(),
                                    s.journeyVersion(),
                                    s.journeyName(),
                                    s.profileId(),
                                    s.status(),
                                    s.currentNodeId(),
                                    s.currentNodeName(),
                                    s.currentNodeType(),
                                    s.enrolledAt(),
                                    s.updatedAt(),
                                    s.terminatedAt(),
                                    rs.getString("termination_reason"),
                                    wake(rs, s.status()),
                                    json.readValue(rs.getString("variables"), MAP),
                                    json.readValue(rs.getString("trigger_snapshot"), TriggerSnapshot.class),
                                    trace(tenantId, enrollmentId, names),
                                    List.of(),
                                    null,
                                    null,
                                    List.of());
                        })
                .optional();
    }

    @Override
    public List<DeliveryEntry> deliveries(
            String tenantId, String enrollmentId, PageCursor after, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "select d.idempotency_key, d.node_id, d.visit_seq, d.template_id, d.channel_id,"
                                + " d.channel_type, d.message_type, d.status, d.skip_reason, d.attempted_at,"
                                + " d.delivery_id, nm.name as node_name"
                                + " from delivery_task d"
                                + " left join enrollment_record e on e.tenant_id = d.tenant_id"
                                + " and e.enrollment_id = d.enrollment_id"
                                + " left join journey_definition jd on jd.tenant_id = e.tenant_id"
                                + " and jd.journey_id = e.journey_id and jd.version = e.journey_version"
                                + " left join lateral (select x ->> 'name' as name"
                                + " from jsonb_array_elements(jd.nodes) x where x ->> 'id' = d.node_id"
                                + " limit 1) nm on true"
                                + " where d.tenant_id = :tenant and d.enrollment_id = :id");
        if (after != null) {
            sql.append(" and (d.attempted_at, d.idempotency_key) < (:at, :key)");
        }
        sql.append(" order by d.attempted_at desc, d.idempotency_key desc limit :limit");
        JdbcClient.StatementSpec spec =
                jdbc.sql(sql.toString())
                        .param("tenant", tenantId)
                        .param("id", enrollmentId)
                        .param("limit", limit);
        if (after != null) {
            spec = spec.param("at", ts(after.at())).param("key", after.id());
        }
        return spec.query((rs, n) -> delivery(rs)).list();
    }

    // ---------- mapping ----------

    private static EnrollmentSummary summary(ResultSet rs) throws SQLException {
        return new EnrollmentSummary(
                rs.getString("enrollment_id"),
                rs.getString("journey_id"),
                rs.getInt("journey_version"),
                rs.getString("journey_name"),
                rs.getString("profile_id"),
                EnrollmentStatus.valueOf(rs.getString("status")),
                rs.getString("current_node_id"),
                rs.getString("node_name"),
                rs.getString("node_type"),
                instant(rs, "enrolled_at"),
                instant(rs, "updated_at"),
                instant(rs, "terminated_at"));
    }

    private static WakeConditionView wake(ResultSet rs, EnrollmentStatus status) throws SQLException {
        String type = rs.getString("wake_type");
        if (type == null || status != EnrollmentStatus.WAITING) {
            return null;
        }
        return new WakeConditionView(
                type,
                instant(rs, "next_wake_at"),
                "EVENT".equals(type) ? rs.getString("wake_event_name") : null,
                null,
                null);
    }

    private List<TraceEntry> trace(String tenantId, String enrollmentId, Map<String, String> names) {
        return jdbc.sql(
                        "select seq, node_id, node_type, entered_at, exited_at, out_port, decision_reason,"
                                + " evaluated from node_trace where tenant_id = :tenant and enrollment_id = :id"
                                + " order by seq")
                .param("tenant", tenantId)
                .param("id", enrollmentId)
                .query(
                        (rs, n) ->
                                new TraceEntry(
                                        rs.getLong("seq"),
                                        rs.getString("node_id"),
                                        rs.getString("node_type"),
                                        names.get(rs.getString("node_id")),
                                        instant(rs, "entered_at"),
                                        instant(rs, "exited_at"),
                                        rs.getString("out_port"),
                                        rs.getString("decision_reason"),
                                        json.readValue(rs.getString("evaluated"), MAP)))
                .list();
    }

    private static DeliveryEntry delivery(ResultSet rs) throws SQLException {
        String messageType = rs.getString("message_type");
        String skipReason = rs.getString("skip_reason");
        return new DeliveryEntry(
                rs.getString("idempotency_key"),
                rs.getString("node_id"),
                rs.getString("node_name"),
                rs.getLong("visit_seq"),
                rs.getString("template_id"),
                rs.getString("channel_id"),
                rs.getString("channel_type"),
                messageType == null ? null : MessageType.valueOf(messageType),
                DeliveryTaskStatus.valueOf(rs.getString("status")),
                skipReason == null ? null : SkipReason.valueOf(skipReason),
                instant(rs, "attempted_at"),
                rs.getString("delivery_id"));
    }

    /** node id -> name（未命名的不放入，查詢得 null）。 */
    private Map<String, String> nodeNames(String nodesJson) {
        Map<String, String> out = new HashMap<>();
        if (nodesJson == null) {
            return out;
        }
        for (JsonNode node : json.readTree(nodesJson)) {
            JsonNode id = node.get("id");
            JsonNode name = node.get("name");
            if (id != null && !id.isNull() && name != null && !name.isNull()) {
                out.put(id.asString(), name.asString());
            }
        }
        return out;
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
