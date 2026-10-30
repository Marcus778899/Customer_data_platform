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
import com.cdp.query.PageCursor;

/** journeys.md §2 第一版推導：每個 journey_id 取 version 最大的一列（最新列）。 */
@Repository
public class JdbcJourneyQueryStore implements JourneyQueryStore {

    private static final String LATEST =
            "select distinct on (journey_id) journey_id, version, name, description, status,"
                    + " created_at, published_at from journey_definition"
                    + " order by journey_id, version desc";

    private final JdbcClient jdbc;

    public JdbcJourneyQueryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<JourneySummary> list(
            String q, Set<JourneyStatus> statuses, PageCursor after, int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "select journey_id, version, name, status, created_at, published_at from ("
                                + LATEST
                                + ") l where true");
        if (q != null) {
            sql.append(" and lower(name) like :pattern escape '\\'");
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and status in (:statuses)");
        }
        if (after != null) {
            sql.append(" and (created_at, journey_id) < (:at, :id)");
        }
        sql.append(" order by created_at desc, journey_id desc limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (q != null) {
            spec = spec.param("pattern", "%" + escapeLike(q.toLowerCase()) + "%");
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (after != null) {
            spec = spec.param("at", ts(after.at())).param("id", after.id());
        }
        return spec.query((rs, n) -> summary(rs)).list();
    }

    @Override
    public Optional<JourneyDetail> detail(String journeyId) {
        Optional<JourneyDetail> latest =
                jdbc.sql(
                                "select journey_id, version, name, description, status, created_at,"
                                        + " published_at from journey_definition where journey_id = :id"
                                        + " order by version desc limit 1")
                        .param("id", journeyId)
                        .query(
                                (rs, n) -> {
                                    JourneySummary s = summary(rs);
                                    return new JourneyDetail(
                                            s.journeyId(),
                                            s.name(),
                                            s.status(),
                                            s.latestVersion(),
                                            false,
                                            null,
                                            s.updatedAt(),
                                            s.publishedAt(),
                                            rs.getString("description"),
                                            List.of(),
                                            null,
                                            null,
                                            null);
                                })
                        .optional();
        if (latest.isEmpty()) {
            return latest;
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
        JourneyDetail d = latest.get();
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
                rs.getInt("version"),
                false,
                null,
                instant(rs, "created_at"),
                instant(rs, "published_at"));
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
