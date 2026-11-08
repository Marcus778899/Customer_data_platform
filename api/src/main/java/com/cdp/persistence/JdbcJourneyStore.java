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

import com.cdp.journey.JourneyStatus;
import com.cdp.journey.JourneyStore;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 表 journey 與草稿、版本寫入（spec 07 §12）。寫入方法依賴呼叫端的交易（鎖與多筆寫入同一交易）。 */
@Repository
public class JdbcJourneyStore implements JourneyStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String COLUMNS =
            "journey_id, status, latest_version, draft, draft_layout, draft_revision, draft_updated_by,"
                    + " draft_updated_at, created_at, updated_at, stopped_at, stop_reason, stopped_by";
    private static final String OPEN_STATUSES = "('PENDING', 'ACTIVE', 'WAITING', 'SUSPENDED')";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcJourneyStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<JourneyRow> find(String journeyId) {
        return jdbc.sql("select " + COLUMNS + " from journey where journey_id = :id")
                .param("id", journeyId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<JourneyRow> lock(String journeyId) {
        return jdbc.sql("select " + COLUMNS + " from journey where journey_id = :id for update")
                .param("id", journeyId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public void insertDraftJourney(String journeyId, Map<String, Object> draft, String userId, Instant now) {
        jdbc.sql(
                        "insert into journey (journey_id, status, latest_version, draft, draft_layout,"
                                + " draft_revision, draft_updated_by, draft_updated_at, created_at, updated_at)"
                                + " values (:id, 'DRAFT', null, cast(:draft as jsonb), null, 1, :user,"
                                + " :now, :now, :now)")
                .param("id", journeyId)
                .param("draft", json.writeValueAsString(draft))
                .param("user", userId)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void ensureRowForExisting(String journeyId, Instant now) {
        jdbc.sql(
                        "insert into journey (journey_id, status, latest_version, created_at, updated_at)"
                                + " select journey_id, 'ACTIVE', max(version), min(created_at), :now"
                                + " from journey_definition where journey_id = :id group by journey_id"
                                + " on conflict (journey_id) do nothing")
                .param("id", journeyId)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void insertPublished(String journeyId, int version, Instant now) {
        jdbc.sql(
                        "insert into journey (journey_id, status, latest_version, created_at, updated_at)"
                                + " values (:id, 'ACTIVE', :version, :now, :now)")
                .param("id", journeyId)
                .param("version", version)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void saveDraft(
            String journeyId,
            int newRevision,
            Map<String, Object> definition,
            Map<String, Object> layout,
            String userId,
            Instant now) {
        jdbc.sql(
                        "update journey set draft = cast(:draft as jsonb), draft_layout = cast(:layout as jsonb),"
                                + " draft_revision = :rev, draft_updated_by = :user, draft_updated_at = :now,"
                                + " updated_at = :now where journey_id = :id")
                .param("id", journeyId)
                .param("draft", json.writeValueAsString(definition))
                .param("layout", layout == null ? null : json.writeValueAsString(layout))
                .param("rev", newRevision)
                .param("user", userId)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void clearDraft(String journeyId, Instant now) {
        jdbc.sql(
                        "update journey set draft = null, draft_layout = null, draft_revision = 0,"
                                + " draft_updated_by = null, draft_updated_at = null, updated_at = :now"
                                + " where journey_id = :id")
                .param("id", journeyId)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void markPublished(String journeyId, int version, boolean clearDraft, Instant now) {
        String draftReset =
                clearDraft
                        ? ", draft = null, draft_layout = null, draft_revision = 0,"
                                + " draft_updated_by = null, draft_updated_at = null"
                        : "";
        jdbc.sql(
                        "update journey set status = 'ACTIVE', latest_version = :version, updated_at = :now"
                                + draftReset
                                + " where journey_id = :id")
                .param("id", journeyId)
                .param("version", version)
                .param("now", ts(now))
                .update();
    }

    @Override
    public void markAborted(String journeyId, String reason, String userId, Instant now) {
        jdbc.sql(
                        "update journey set status = 'ABORTED', stopped_at = :now, stop_reason = :reason,"
                                + " stopped_by = :user, updated_at = :now where journey_id = :id")
                .param("id", journeyId)
                .param("reason", reason)
                .param("user", userId)
                .param("now", ts(now))
                .update();
        // findActiveEventSources 只看 ACTIVE 的最新版本：停止後不再有新進入
        jdbc.sql("update journey_definition set status = 'ABORTED' where journey_id = :id")
                .param("id", journeyId)
                .update();
    }

    @Override
    public int insertVersion(
            String journeyId, Map<String, Object> definition, Map<String, Object> layout, Instant now) {
        return jdbc.sql(
                        "insert into journey_definition (journey_id, version, name, description, status,"
                                + " settings, entry_node, nodes, created_at, published_at, layout)"
                                + " select cast(:id as text), coalesce(max(version), 0) + 1,"
                                + " cast(:name as text), cast(:description as text), 'ACTIVE',"
                                + " cast(:settings as jsonb), cast(:entry as text), cast(:nodes as jsonb),"
                                + " :now, :now, cast(:layout as jsonb)"
                                + " from journey_definition where journey_id = :id returning version")
                .param("id", journeyId)
                .param("name", definition.get("name"))
                .param("description", definition.get("description"))
                .param("settings", json.writeValueAsString(orEmpty(definition.get("settings"))))
                .param("entry", definition.get("entry_node"))
                .param("nodes", json.writeValueAsString(definition.get("nodes")))
                .param("now", ts(now))
                .param("layout", layout == null ? null : json.writeValueAsString(layout))
                .query(Integer.class)
                .single();
    }

    private static Object orEmpty(Object settings) {
        return settings == null ? Map.of() : settings;
    }

    @Override
    public boolean versionExists(String journeyId, int version) {
        return jdbc.sql("select count(*) from journey_definition where journey_id = :id and version = :v")
                        .param("id", journeyId)
                        .param("v", version)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    @Override
    public Optional<Map<String, Object>> findLayout(String journeyId, int version) {
        return jdbc.sql("select layout from journey_definition where journey_id = :id and version = :v")
                .param("id", journeyId)
                .param("v", version)
                .query((rs, n) -> rs.getString("layout"))
                .optional()
                .map(s -> json.readValue(s, MAP));
    }

    @Override
    public boolean isAborted(String journeyId) {
        return jdbc.sql("select count(*) from journey where journey_id = :id and status = 'ABORTED'")
                        .param("id", journeyId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    @Override
    public List<StoppedEnrollment> lockOpenEnrollmentsOfStoppedJourneys(int limit) {
        return jdbc.sql(
                        "select e.enrollment_id, e.journey_id, e.journey_version, j.stop_reason"
                                + " from enrollment_record e join journey j on j.journey_id = e.journey_id"
                                + " where j.status = 'ABORTED' and e.status in " + OPEN_STATUSES
                                + " order by e.enrollment_id limit :limit for update of e skip locked")
                .param("limit", limit)
                .query(
                        (rs, n) ->
                                new StoppedEnrollment(
                                        rs.getString("enrollment_id"),
                                        rs.getString("journey_id"),
                                        rs.getInt("journey_version"),
                                        rs.getString("stop_reason")))
                .list();
    }

    @Override
    public List<String> profileAttributeKeys(int limit) {
        return jdbc.sql(
                        "select distinct k from (select attributes from profile limit :limit) p,"
                                + " jsonb_object_keys(p.attributes) as k order by k collate \"C\"")
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    @Override
    public List<String> eventPropertyKeys(String eventName, int limit) {
        return jdbc.sql(
                        "select distinct k from (select properties from event where name = :name"
                                + " and properties is not null and jsonb_typeof(properties) = 'object'"
                                + " order by occurred_at desc limit :limit) e,"
                                + " jsonb_object_keys(e.properties) as k order by k collate \"C\"")
                .param("name", eventName)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    private JourneyRow map(ResultSet rs) throws SQLException {
        String draft = rs.getString("draft");
        String layout = rs.getString("draft_layout");
        int latest = rs.getInt("latest_version");
        Integer latestVersion = rs.wasNull() ? null : latest;
        return new JourneyRow(
                rs.getString("journey_id"),
                JourneyStatus.valueOf(rs.getString("status")),
                latestVersion,
                draft == null ? null : json.readValue(draft, MAP),
                layout == null ? null : json.readValue(layout, MAP),
                rs.getInt("draft_revision"),
                rs.getString("draft_updated_by"),
                instant(rs, "draft_updated_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "stopped_at"),
                rs.getString("stop_reason"),
                rs.getString("stopped_by"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
