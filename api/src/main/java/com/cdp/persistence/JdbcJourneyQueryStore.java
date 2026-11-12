package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.journey.JourneyDetail;
import com.cdp.journey.JourneyQueryStore;
import com.cdp.journey.JourneyStatus;
import com.cdp.journey.JourneySummary;
import com.cdp.journey.JourneyVersionRef;
import com.cdp.journey.ReviewBrief;
import com.cdp.journey.ReviewStatus;
import com.cdp.journey.UserRef;
import com.cdp.query.PageCursor;

/**
 * journeys.md §2 第二版：讀 journey 表，名稱、說明、發佈時間取最新版本（DRAFT 取草稿）；review_status 由該旅程最新一筆
 * journey_review 與草稿推導（送審中 PENDING；最新一筆被退回且草稿仍在 REJECTED；其他 null）。
 */
@Repository
public class JdbcJourneyQueryStore implements JourneyQueryStore {

    private static final String BASE =
            "select j.journey_id, j.status, j.latest_version,"
                    + " coalesce(d.name, j.draft ->> 'name') as name,"
                    + " case when j.latest_version is null then j.draft ->> 'description'"
                    + " else d.description end as description,"
                    + " (j.draft is not null) as has_draft, j.updated_at, d.published_at,"
                    + " j.stopped_at, j.stop_reason, j.stopped_by, su.display_name as stopped_name,"
                    + " case when lr.status = 'PENDING' then 'PENDING'"
                    + " when lr.status = 'REJECTED' and j.draft is not null then 'REJECTED' end as review_status,"
                    + " lr.review_id, lr.status as lr_status, lr.submitted_by as lr_submitted_by,"
                    + " sb.display_name as lr_submitted_name, lr.submitted_at as lr_submitted_at,"
                    + " lr.decided_by as lr_decided_by, db.display_name as lr_decided_name,"
                    + " lr.decided_at as lr_decided_at, lr.reason as lr_reason"
                    + " from journey j"
                    + " left join journey_definition d on d.journey_id = j.journey_id"
                    + " and d.version = j.latest_version"
                    + " left join app_user su on su.user_id = j.stopped_by"
                    + " left join lateral (select * from journey_review r where r.journey_id = j.journey_id"
                    + " order by (r.status = 'PENDING') desc, r.submitted_at desc, r.seq desc limit 1) lr on true"
                    + " left join app_user sb on sb.user_id = lr.submitted_by"
                    + " left join app_user db on db.user_id = lr.decided_by";

    private final JdbcClient jdbc;

    public JdbcJourneyQueryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<JourneySummary> list(
            String q, Set<JourneyStatus> statuses, String reviewStatus, PageCursor after, int limit) {
        StringBuilder sql = new StringBuilder("select * from (" + BASE + ") l where true");
        if (q != null) {
            sql.append(" and lower(name) like :pattern escape '\\'");
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and status in (:statuses)");
        }
        if (reviewStatus != null) {
            sql.append(" and review_status = :reviewStatus");
        }
        if (after != null) {
            sql.append(" and (updated_at, journey_id) < (:at, :id)");
        }
        sql.append(" order by updated_at desc, journey_id desc limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (q != null) {
            spec = spec.param("pattern", "%" + escapeLike(q.toLowerCase()) + "%");
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (reviewStatus != null) {
            spec = spec.param("reviewStatus", reviewStatus);
        }
        if (after != null) {
            spec = spec.param("at", ts(after.at())).param("id", after.id());
        }
        return spec.query((rs, n) -> summary(rs)).list();
    }

    @Override
    public Optional<JourneyDetail> detail(String journeyId) {
        Optional<JourneyDetail> found =
                jdbc.sql(BASE + " where j.journey_id = :id")
                        .param("id", journeyId)
                        .query((rs, n) -> detailRow(rs))
                        .optional();
        if (found.isEmpty()) {
            return found;
        }
        List<JourneyVersionRef> versions =
                jdbc.sql(
                                "select version, published_at from journey_definition"
                                        + " where journey_id = :id order by version desc")
                        .param("id", journeyId)
                        .query(
                                (rs, n) ->
                                        new JourneyVersionRef(
                                                rs.getInt("version"), instant(rs, "published_at")))
                        .list();
        JourneyDetail d = found.get();
        return Optional.of(
                new JourneyDetail(
                        d.journeyId(),
                        d.name(),
                        d.status(),
                        d.latestVersion(),
                        d.hasDraft(),
                        d.reviewStatus(),
                        d.updatedAt(),
                        d.publishedAt(),
                        d.latestReview(),
                        d.description(),
                        versions,
                        d.stoppedAt(),
                        d.stopReason(),
                        d.stoppedBy()));
    }

    private static JourneySummary summary(ResultSet rs) throws SQLException {
        return new JourneySummary(
                rs.getString("journey_id"),
                rs.getString("name"),
                JourneyStatus.valueOf(rs.getString("status")),
                JdbcJourneyReviewStore.integer(rs, "latest_version"),
                rs.getBoolean("has_draft"),
                rs.getString("review_status"),
                instant(rs, "updated_at"),
                instant(rs, "published_at"),
                brief(rs));
    }

    private static JourneyDetail detailRow(ResultSet rs) throws SQLException {
        JourneySummary s = summary(rs);
        String stoppedBy = rs.getString("stopped_by");
        String stoppedName = rs.getString("stopped_name");
        return new JourneyDetail(
                s.journeyId(),
                s.name(),
                s.status(),
                s.latestVersion(),
                s.hasDraft(),
                s.reviewStatus(),
                s.updatedAt(),
                s.publishedAt(),
                s.latestReview(),
                rs.getString("description"),
                List.of(),
                instant(rs, "stopped_at"),
                rs.getString("stop_reason"),
                stoppedBy == null ? null : new UserRef(stoppedBy, stoppedName == null ? stoppedBy : stoppedName));
    }

    /** 只有 review_status 不為 null 時帶出那一筆審核的摘要。 */
    private static ReviewBrief brief(ResultSet rs) throws SQLException {
        if (rs.getString("review_status") == null) {
            return null;
        }
        return new ReviewBrief(
                rs.getString("review_id"),
                ReviewStatus.valueOf(rs.getString("lr_status")),
                JdbcJourneyReviewStore.user(rs.getString("lr_submitted_by"), rs.getString("lr_submitted_name")),
                instant(rs, "lr_submitted_at"),
                JdbcJourneyReviewStore.user(rs.getString("lr_decided_by"), rs.getString("lr_decided_name")),
                instant(rs, "lr_decided_at"),
                rs.getString("lr_reason"));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
