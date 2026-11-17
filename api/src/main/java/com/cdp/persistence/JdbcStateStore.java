package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStatus;
import com.cdp.delivery.OutboxStore;
import com.cdp.engine.DuplicateEnrollmentException;
import com.cdp.engine.StateStore;
import com.cdp.engine.Timer;
import com.cdp.engine.model.DeliveryRecord;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.engine.model.EnrollmentTrace;
import com.cdp.engine.model.TriggerSnapshot;
import com.cdp.engine.model.WakeCondition;
import com.cdp.engine.node.Effect;
import com.cdp.engine.runtime.ReentryStore;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** enrollment_record + node_trace；save 內呼叫 Timer 與 OutboxStore（同一交易）。 */
@Repository
public class JdbcStateStore implements StateStore, ReentryStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<DeliveryRecord>> HISTORY = new TypeReference<>() {};
    private static final String ENROLLMENT_COLUMNS =
            "enrollment_id, journey_id, journey_version, profile_id, status,"
                    + " current_node_id, wake_type, next_wake_at, wake_event_name, wake_event_filter,"
                    + " trigger_snapshot, dedup_key, variables, delivery_history, enrolled_at,"
                    + " updated_at, terminated_at, termination_reason";

    /** 到期推進的搶列 SQL（具名參數 :now、:limit）；public 供驗收測試 EXPLAIN。 */
    public static final String LOCK_DUE_SQL =
            "select " + ENROLLMENT_COLUMNS + " from enrollment_record"
                    + " where status = 'WAITING' and next_wake_at is not null"
                    + " and next_wake_at <= :now order by next_wake_at, enrollment_id"
                    + " limit :limit for update skip locked";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Timer timer;
    private final OutboxStore outbox;

    public JdbcStateStore(JdbcClient jdbc, JsonMapper json, Timer timer, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.timer = timer;
        this.outbox = outbox;
    }

    @Override
    public EnrollmentState create(EnrollmentState s) throws DuplicateEnrollmentException {
        int rows =
                jdbc.sql(
                                "insert into enrollment_record (enrollment_id, journey_id,"
                                        + " journey_version, profile_id, status, current_node_id,"
                                        + " trigger_snapshot, dedup_key, trigger_event_id, variables,"
                                        + " delivery_history, enrolled_at, updated_at)"
                                        + " values (:id, :journey, :version, :profile, :status,"
                                        + " :node, cast(:trigger as jsonb), :dedup, :triggerEvent,"
                                        + " cast(:vars as jsonb),"
                                        + " cast(:history as jsonb), :enrolledAt, :updatedAt)"
                                        + " on conflict do nothing")
                        .param("id", s.enrollmentId())
                        .param("journey", s.journeyId())
                        .param("version", s.journeyVersion())
                        .param("profile", s.profileId())
                        .param("status", s.status().name())
                        .param("node", s.currentNodeId())
                        .param("trigger", json.writeValueAsString(s.triggerSnapshot()))
                        .param("dedup", s.dedupKey())
                        .param("triggerEvent", triggerEventId(s))
                        .param("vars", json.writeValueAsString(s.variables()))
                        .param("history", json.writeValueAsString(s.deliveryHistory()))
                        .param("enrolledAt", ts(s.enrolledAt()))
                        .param("updatedAt", ts(s.updatedAt()))
                        .update();
        if (rows == 0) {
            // 撞 uq_enrollment_trigger（同一事件重送）或 uq_enrollment_dedup（G3）；以資料庫約束為準
            String existing =
                    jdbc.sql(
                                    "select enrollment_id from enrollment_record where journey_id = :journey"
                                            + " and (trigger_event_id = :te"
                                            + " or (profile_id = :profile and dedup_key = :dedup))"
                                            + " order by (trigger_event_id = :te) desc nulls last limit 1")
                            .param("journey", s.journeyId())
                            .param("profile", s.profileId())
                            .param("dedup", s.dedupKey())
                            .param("te", triggerEventId(s))
                            .query(String.class)
                            .optional()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "enrollment insert conflict without existing row"));
            throw new DuplicateEnrollmentException(existing);
        }
        return s;
    }

    private static String triggerEventId(EnrollmentState s) {
        return s.triggerSnapshot() == null ? null : s.triggerSnapshot().eventId();
    }

    @Override
    public void lockProfile(String journeyId, String profileId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", journeyId + ":" + profileId)
                .query((rs, n) -> 1)
                .single();
    }

    @Override
    public Optional<String> findByTriggerEvent(String journeyId, String triggerEventId) {
        return jdbc.sql(
                        "select enrollment_id from enrollment_record"
                                + " where journey_id = :journey and trigger_event_id = :te")
                .param("journey", journeyId)
                .param("te", triggerEventId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> findCooldownBlocker(String journeyId, String profileId, Instant cutoff) {
        return jdbc.sql(
                        "select enrollment_id from enrollment_record"
                                + " where journey_id = :journey and profile_id = :profile"
                                + " and (terminated_at is null or terminated_at > :cutoff)"
                                + " order by enrolled_at desc, enrollment_id desc limit 1")
                .param("journey", journeyId)
                .param("profile", profileId)
                .param("cutoff", ts(cutoff))
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<EnrollmentState> findById(String enrollmentId) {
        return jdbc.sql(
                        "select " + ENROLLMENT_COLUMNS + " from enrollment_record"
                                + " where enrollment_id = :id")
                .param("id", enrollmentId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<EnrollmentState> lockById(String enrollmentId) {
        return jdbc.sql(
                        "select " + ENROLLMENT_COLUMNS + " from enrollment_record"
                                + " where enrollment_id = :id for update")
                .param("id", enrollmentId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public List<EnrollmentState> findOpenByProfile(String profileId) {
        return jdbc.sql(
                        "select " + ENROLLMENT_COLUMNS + " from enrollment_record"
                                + " where profile_id = :profile"
                                + " and status in ('PENDING', 'ACTIVE', 'WAITING', 'SUSPENDED')"
                                + " order by enrolled_at, enrollment_id")
                .param("profile", profileId)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public List<EnrollmentState> lockDue(Instant now, int limit) {
        return jdbc.sql(LOCK_DUE_SQL)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public List<EnrollmentState> findWaitingForEvent(
            String profileId, String eventName) {
        return jdbc.sql(
                        "select " + ENROLLMENT_COLUMNS + " from enrollment_record"
                                + " where profile_id = :profile"
                                + " and wake_event_name = :name and wake_type = 'EVENT'"
                                + " and status = 'WAITING' order by enrolled_at, enrollment_id")
                .param("profile", profileId)
                .param("name", eventName)
                .query((rs, n) -> map(rs))
                .list();
    }

    @Override
    public Optional<EnrollmentTrace> findOpenTrace(String enrollmentId) {
        return jdbc.sql(
                        "select enrollment_id, seq, node_id, node_type, entered_at, exited_at, out_port,"
                                + " decision_reason, evaluated from node_trace"
                                + " where enrollment_id = :id"
                                + " and exited_at is null order by seq desc limit 1")
                .param("id", enrollmentId)
                .query(
                        (rs, n) ->
                                new EnrollmentTrace(
                                        rs.getString("enrollment_id"),
                                        rs.getLong("seq"),
                                        rs.getString("node_id"),
                                        rs.getString("node_type"),
                                        instant(rs, "entered_at"),
                                        instant(rs, "exited_at"),
                                        rs.getString("out_port"),
                                        rs.getString("decision_reason"),
                                        json.readValue(rs.getString("evaluated"), MAP)))
                .optional();
    }

    /** 不寫 delivery_history：那一欄由 consumer 追加，這裡覆寫會蓋掉並行寫入。 */
    @Override
    @Transactional
    public void save(EnrollmentState s, EnrollmentTrace trace, List<Effect> effects) {
        WakeCondition wake = s.wakeCondition();
        String wakeType = null;
        String wakeEventName = null;
        String wakeEventFilter = null;
        if (wake instanceof WakeCondition.Timer) {
            wakeType = "TIMER";
        } else if (wake instanceof WakeCondition.Event e) {
            wakeType = "EVENT";
            wakeEventName = e.eventName();
            wakeEventFilter = e.filter() == null ? null : json.writeValueAsString(e.filter());
        } else if (wake instanceof WakeCondition.Signal sig) {
            wakeType = "SIGNAL";
            wakeEventName = sig.signalName();
        }

        int updated =
                jdbc.sql(
                                "update enrollment_record set status = :status,"
                                        + " current_node_id = :node, wake_type = :wakeType,"
                                        + " wake_event_name = :wakeEvent,"
                                        + " wake_event_filter = cast(:wakeFilter as jsonb),"
                                        + " variables = cast(:vars as jsonb), updated_at = :updatedAt,"
                                        + " terminated_at = :terminatedAt,"
                                        + " termination_reason = :reason"
                                        + " where enrollment_id = :id")
                        .param("status", s.status().name())
                        .param("node", s.currentNodeId())
                        .param("wakeType", wakeType)
                        .param("wakeEvent", wakeEventName)
                        .param("wakeFilter", wakeEventFilter)
                        .param("vars", json.writeValueAsString(s.variables()))
                        .param("updatedAt", ts(s.updatedAt()))
                        .param("terminatedAt", ts(s.terminatedAt()))
                        .param("reason", s.terminationReason())
                        .param("id", s.enrollmentId())
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("enrollment not found: " + s.enrollmentId());
        }

        // next_wake_at 只由 Timer 寫（R1），與上面的更新同一交易
        if (wake instanceof WakeCondition.Timer t) {
            timer.schedule(s.enrollmentId(), t.wakeAt());
        } else if (wake instanceof WakeCondition.Event e && e.timeoutAt() != null) {
            timer.schedule(s.enrollmentId(), e.timeoutAt());
        } else {
            timer.cancel(s.enrollmentId());
        }

        // seq <= 0（APPEND_TRACE）：喚醒時找不到 open trace，接在既有軌跡之後
        long seq =
                trace.seq() > 0
                        ? trace.seq()
                        : jdbc.sql(
                                        "select coalesce(max(seq), 0) + 1 from node_trace"
                                                + " where enrollment_id = :id")
                                .param("id", trace.enrollmentId())
                                .query(Long.class)
                                .single();

        // 軌跡：等待列進入時寫一列，喚醒離開時以同 seq 補完一次；已離開的列不得再改（07 §5）
        jdbc.sql(
                        "insert into node_trace (enrollment_id, seq, node_id, node_type,"
                                + " entered_at, exited_at, out_port, decision_reason, evaluated)"
                                + " values (:id, :seq, :node, :type, :entered, :exited, :port,"
                                + " :reason, cast(:evaluated as jsonb))"
                                + " on conflict (enrollment_id, seq) do update set"
                                + " exited_at = excluded.exited_at, out_port = excluded.out_port,"
                                + " decision_reason = excluded.decision_reason,"
                                + " evaluated = excluded.evaluated"
                                + " where node_trace.exited_at is null")
                .param("id", trace.enrollmentId())
                .param("seq", seq)
                .param("node", trace.nodeId())
                .param("type", trace.nodeType())
                .param("entered", ts(trace.enteredAt()))
                .param("exited", ts(trace.exitedAt()))
                .param("port", trace.outPort())
                .param("reason", trace.decisionReason())
                .param("evaluated", json.writeValueAsString(trace.evaluated()))
                .update();

        if (effects != null && !effects.isEmpty()) {
            List<OutboxEntry> entries = new ArrayList<>(effects.size());
            for (int i = 0; i < effects.size(); i++) {
                Effect effect = effects.get(i);
                entries.add(
                        new OutboxEntry(
                                effect.idempotencyKey(),
                                s.enrollmentId(),
                                s.journeyId(),
                                s.journeyVersion(),
                                trace.nodeId(),
                                seq,
                                i,
                                effectType(effect),
                                json.convertValue(effect, MAP),
                                OutboxStatus.PENDING,
                                s.updatedAt(),
                                0,
                                s.updatedAt(),
                                null));
            }
            outbox.insertAll(entries);
        }
    }

    /** effect_type 明確對照（spec 07 §10）：改類別名不會悄悄改變已寫入的值。 */
    static String effectType(Effect effect) {
        if (effect instanceof Effect.SendMessage) {
            return "SendMessage";
        }
        throw new IllegalArgumentException("unmapped effect type " + effect.getClass().getName());
    }

    private EnrollmentState map(ResultSet rs) throws SQLException {
        String wakeType = rs.getString("wake_type");
        Instant nextWakeAt = instant(rs, "next_wake_at");
        WakeCondition wake = null;
        if (wakeType != null) {
            String filter = rs.getString("wake_event_filter");
            wake =
                    switch (wakeType) {
                        case "TIMER" -> new WakeCondition.Timer(nextWakeAt);
                        case "EVENT" ->
                                new WakeCondition.Event(
                                        rs.getString("wake_event_name"),
                                        filter == null ? null : json.readValue(filter, MAP),
                                        nextWakeAt);
                        case "SIGNAL" -> new WakeCondition.Signal(rs.getString("wake_event_name"));
                        default -> throw new IllegalStateException("unknown wake_type " + wakeType);
                    };
        }
        return new EnrollmentState(
                rs.getString("enrollment_id"),
                rs.getString("journey_id"),
                rs.getInt("journey_version"),
                rs.getString("profile_id"),
                EnrollmentStatus.valueOf(rs.getString("status")),
                rs.getString("current_node_id"),
                wake,
                json.readValue(rs.getString("trigger_snapshot"), TriggerSnapshot.class),
                rs.getString("dedup_key"),
                json.readValue(rs.getString("variables"), MAP),
                json.readValue(rs.getString("delivery_history"), HISTORY),
                instant(rs, "enrolled_at"),
                instant(rs, "updated_at"),
                instant(rs, "terminated_at"),
                rs.getString("termination_reason"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
