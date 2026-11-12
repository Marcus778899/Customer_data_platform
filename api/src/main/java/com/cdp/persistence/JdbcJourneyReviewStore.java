package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.journey.JourneyReview;
import com.cdp.journey.JourneyReviewStore;
import com.cdp.journey.ReviewStatus;
import com.cdp.journey.UserRef;
import com.cdp.query.PageCursor;

/** 表 journey_review；操作者的顯示名稱 join app_user（停用者照常帶出）。 */
@Repository
public class JdbcJourneyReviewStore implements JourneyReviewStore {

    /** 共用 SELECT 與 join；r = journey_review，sb／db = 送審者／決定者。 */
    static final String SELECT =
            "select r.review_id, r.journey_id, r.action, r.status, r.revision, r.base_version, r.note,"
                    + " r.submitted_by, sb.display_name as submitted_name, r.submitted_at,"
                    + " r.decided_by, db.display_name as decided_name, r.decided_at, r.reason, r.version"
                    + " from journey_review r"
                    + " left join app_user sb on sb.user_id = r.submitted_by"
                    + " left join app_user db on db.user_id = r.decided_by";

    private final JdbcClient jdbc;

    public JdbcJourneyReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(JourneyReview r) {
        jdbc.sql(
                        "insert into journey_review (review_id, journey_id, action, status, revision,"
                                + " base_version, note, submitted_by, submitted_at, decided_by, decided_at,"
                                + " reason, version) values (:id, :journey, :action, :status, :revision,"
                                + " :base, :note, :submittedBy, :submittedAt, :decidedBy, :decidedAt,"
                                + " :reason, :version)")
                .param("id", r.reviewId())
                .param("journey", r.journeyId())
                .param("action", r.action())
                .param("status", r.status().name())
                .param("revision", r.revision())
                .param("base", r.baseVersion())
                .param("note", r.note())
                .param("submittedBy", r.submittedBy() == null ? null : r.submittedBy().userId())
                .param("submittedAt", ts(r.submittedAt()))
                .param("decidedBy", r.decidedBy() == null ? null : r.decidedBy().userId())
                .param("decidedAt", ts(r.decidedAt()))
                .param("reason", r.reason())
                .param("version", r.version())
                .update();
    }

    @Override
    public Optional<JourneyReview> find(String journeyId, String reviewId) {
        return jdbc.sql(SELECT + " where r.journey_id = :journey and r.review_id = :id")
                .param("journey", journeyId)
                .param("id", reviewId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<JourneyReview> findPending(String journeyId) {
        return jdbc.sql(SELECT + " where r.journey_id = :journey and r.status = 'PENDING'")
                .param("journey", journeyId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<JourneyReview> latest(String journeyId) {
        return jdbc.sql(
                        SELECT
                                + " where r.journey_id = :journey"
                                + " order by (r.status = 'PENDING') desc, r.submitted_at desc, r.seq desc limit 1")
                .param("journey", journeyId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public boolean decide(
            String reviewId,
            ReviewStatus status,
            String decidedBy,
            Instant decidedAt,
            String reason,
            Integer version) {
        return jdbc.sql(
                                "update journey_review set status = :status, decided_by = :by,"
                                        + " decided_at = :at, reason = :reason, version = :version"
                                        + " where review_id = :id and status = 'PENDING'")
                        .param("id", reviewId)
                        .param("status", status.name())
                        .param("by", decidedBy)
                        .param("at", ts(decidedAt))
                        .param("reason", reason)
                        .param("version", version)
                        .update()
                == 1;
    }

    @Override
    public List<JourneyReview> list(String journeyId, PageCursor after, int limit) {
        String cursor = after == null ? "" : " and (r.submitted_at, r.seq) < (:at, (select c.seq from journey_review c where c.review_id = :cursorId))";
        JdbcClient.StatementSpec spec =
                jdbc.sql(
                                SELECT
                                        + " where r.journey_id = :journey"
                                        + cursor
                                        + " order by r.submitted_at desc, r.seq desc limit :limit")
                        .param("journey", journeyId)
                        .param("limit", limit);
        if (after != null) {
            spec = spec.param("at", ts(after.at())).param("cursorId", after.id());
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    static JourneyReview map(ResultSet rs) throws SQLException {
        return new JourneyReview(
                rs.getString("review_id"),
                rs.getString("journey_id"),
                rs.getString("action"),
                ReviewStatus.valueOf(rs.getString("status")),
                integer(rs, "revision"),
                integer(rs, "base_version"),
                rs.getString("note"),
                user(rs.getString("submitted_by"), rs.getString("submitted_name")),
                instant(rs, "submitted_at"),
                user(rs.getString("decided_by"), rs.getString("decided_name")),
                instant(rs, "decided_at"),
                rs.getString("reason"),
                integer(rs, "version"));
    }

    static UserRef user(String id, String name) {
        return id == null ? null : new UserRef(id, name == null ? id : name);
    }

    static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
