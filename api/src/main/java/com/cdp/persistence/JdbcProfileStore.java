package com.cdp.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.profile.IdentifierType;
import com.cdp.profile.ProfileReadStore;
import com.cdp.profile.ProfileRecord;
import com.cdp.profile.StoredIdentifier;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 客戶資料的唯讀查詢，每次即時查表、不快取（DESIGN-03 做法 #4、#5）。寫入只在 ProfileWriter。 */
@Repository
public class JdbcProfileStore implements ProfileReadStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcProfileStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ProfileRecord> find(String profileId) {
        return jdbc.sql(
                        "select profile_id, attributes::text as attributes, created_at, updated_at"
                                + " from profile where profile_id = :id")
                .param("id", profileId)
                .query((rs, n) -> record(rs))
                .optional();
    }

    @Override
    public boolean exists(String profileId) {
        return jdbc.sql("select count(*) from profile where profile_id = :id")
                        .param("id", profileId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    @Override
    public List<ProfileRecord> findAll(Collection<String> profileIds) {
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "select profile_id, attributes::text as attributes, created_at, updated_at"
                                + " from profile where profile_id in (:ids)")
                .param("ids", profileIds)
                .query((rs, n) -> record(rs))
                .list();
    }

    @Override
    public Map<String, List<StoredIdentifier>> identifiersOf(Collection<String> profileIds) {
        Map<String, List<StoredIdentifier>> out = new LinkedHashMap<>();
        if (profileIds.isEmpty()) {
            return out;
        }
        List<StoredIdentifier> rows =
                jdbc.sql(
                                "select profile_id, id_type, id_value from profile_identifier"
                                        + " where profile_id in (:ids)")
                        .param("ids", profileIds)
                        .query(
                                (rs, n) ->
                                        new StoredIdentifier(
                                                rs.getString("profile_id"),
                                                IdentifierType.valueOf(rs.getString("id_type")),
                                                rs.getString("id_value")))
                        .list();
        for (StoredIdentifier r : rows) {
            out.computeIfAbsent(r.profileId(), k -> new ArrayList<>()).add(r);
        }
        out.values()
                .forEach(
                        l ->
                                l.sort(
                                        Comparator.comparing((StoredIdentifier i) -> i.type().name())
                                                .thenComparing(StoredIdentifier::value)));
        return out;
    }

    @Override
    public Optional<String> findByIdentifier(IdentifierType type, String value) {
        return jdbc.sql(
                        "select profile_id from profile_identifier"
                                + " where id_type = :type and id_value = :value")
                .param("type", type.name())
                .param("value", value)
                .query(String.class)
                .optional();
    }

    private ProfileRecord record(ResultSet rs) throws SQLException {
        return new ProfileRecord(
                rs.getString("profile_id"),
                json.readValue(rs.getString("attributes"), MAP),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
