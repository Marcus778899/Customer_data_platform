package com.cdp.acceptance.profilesearchapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * profile-search-api 驗收：spec 07 §12 V7——{@code profile}、{@code profile_identifier}（PK、正規化 CHECK、屬性大小 CHECK）、
 * 自既有 Enrollment 與事件回填空白客戶、{@code enrollment_record} 兩個瀏覽索引。獨立容器、直接用 Flyway，先升到 6 灌舊資料再升到 7。
 */
class SchemaV7ProfileStoreAcceptanceTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-10-22T02:00:00Z"));

    private PostgreSQLContainer pg;
    private JdbcTemplate jdbc;

    @BeforeEach
    void start() {
        pg = new PostgreSQLContainer("postgres:18-alpine");
        pg.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @AfterEach
    void stop() {
        pg.stop();
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void insertEnrollment(String id, String profile) {
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                        + " current_node_id, trigger_snapshot, enrolled_at, updated_at)"
                        + " values (?,?,1,?,'WAITING','wait_1','{}'::jsonb,?,?)",
                id,
                "J1",
                profile,
                T0,
                T0);
    }

    private void insertEvent(String id, String identityJson) {
        jdbc.update(
                "insert into event(event_id, source_type, name, occurred_at, received_at, identity)"
                        + " values (?,?,?,?,?,?::jsonb)",
                id,
                "CUSTOMER_BEHAVIOR",
                "evt_x",
                T0,
                T0,
                identityJson);
    }

    private void insertProfile(String id) {
        jdbc.update("insert into profile(profile_id, attributes, created_at, updated_at) values (?, '{}'::jsonb, ?, ?)", id, T0, T0);
    }

    private void insertIdentifier(String type, String value, String profile) {
        jdbc.update(
                "insert into profile_identifier(id_type, id_value, profile_id, created_at) values (?,?,?,?)",
                type,
                value,
                profile,
                T0);
    }

    @Test
    void v7_backfillsBlankProfilesFromEnrollmentsAndEvents_withoutDuplicates() {
        migrate("6");
        insertEnrollment("E1", "P-ENR-1");
        insertEnrollment("E2", "P-ENR-1"); // 同一客戶兩筆
        insertEnrollment("E3", "P-ENR-2");
        insertEvent("EV1", "{\"profile_id\":\"P-EVT-1\"}");
        insertEvent("EV2", "{\"profile_id\":\"P-ENR-1\"}"); // 與 Enrollment 重疊
        insertEvent("EV3", "{\"profile_id\":\"P-EVT-1\"}");
        insertEvent("EV4", "{}"); // 沒有 profile_id 的舊事件：不可讓 migration 失敗，也不可建 NULL 客戶

        migrate("7");

        List<String> ids = jdbc.queryForList("select profile_id from profile order by profile_id", String.class);
        assertThat(ids).containsExactly("P-ENR-1", "P-ENR-2", "P-EVT-1");
        assertThat(jdbc.queryForObject("select count(*) from profile where attributes <> '{}'::jsonb", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from profile where created_at is null or updated_at is null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from profile_identifier", Integer.class)).isZero();
    }

    @Test
    void v7_onEmptyDatabase_createsEmptyTables() {
        migrate("7");
        assertThat(jdbc.queryForObject("select count(*) from profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from profile_identifier", Integer.class)).isZero();
    }

    @Test
    void v7_createsTheTwoEnrollmentBrowseIndexes_andIdentifierIndex() {
        migrate("7");
        List<String> defs =
                jdbc.queryForList(
                        "select indexdef from pg_indexes where tablename = 'enrollment_record'"
                                + " and indexname in ('ix_enrollment_recent','ix_enrollment_journey_recent') order by indexname",
                        String.class);
        assertThat(defs).hasSize(2);
        assertThat(defs.get(0)).contains("ix_enrollment_journey_recent").contains("(journey_id, enrolled_at DESC, enrollment_id DESC)");
        assertThat(defs.get(1)).contains("ix_enrollment_recent").contains("(enrolled_at DESC, enrollment_id DESC)");
        Set<String> idx =
                new TreeSet<>(
                        jdbc.queryForList(
                                "select indexname from pg_indexes where tablename = 'profile_identifier'", String.class));
        assertThat(idx).contains("ix_profile_identifier_profile");
    }

    @Test
    void v7_doesNotTouchExistingIndexes() {
        migrate("6");
        Set<String> before =
                new TreeSet<>(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class));
        migrate("7");
        Set<String> after =
                new TreeSet<>(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class));
        assertThat(after).containsAll(before);
    }

    @Test
    void profile_attributesOver32Kb_isRejectedByCheck() {
        migrate("7");
        insertProfile("P1");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update profile set attributes = jsonb_build_object('k', repeat('x', 33000)) where profile_id = 'P1'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update profile set attributes = jsonb_build_object('k', repeat('x', 30000)) where profile_id = 'P1'");
    }

    @Test
    void identifier_primaryKeyIsTypeAndValue_oneIdentifierBelongsToOneCustomer() {
        migrate("7");
        insertProfile("P1");
        insertProfile("P2");
        insertIdentifier("EMAIL", "a@example.com", "P1");
        assertThatThrownBy(() -> insertIdentifier("EMAIL", "a@example.com", "P2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertIdentifier("EMAIL", "a@example.com", "P1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertIdentifier("MEMBER_ID", "a@example.com", "P2"); // 不同 type 同值可以
    }

    @Test
    void identifier_normalizationChecks() {
        migrate("7");
        insertProfile("P1");
        // 合法
        insertIdentifier("EMAIL", "ok@example.com", "P1");
        insertIdentifier("PHONE", "+886912345678", "P1");
        insertIdentifier("MEMBER_ID", "Mixed-Case_1", "P1");
        insertIdentifier("LINE_USER_ID", "U123", "P1");
        insertIdentifier("DEVICE_ID", "d".repeat(200), "P1");

        List<String[]> bad =
                List.of(
                        new String[] {"EMAIL", "Upper@example.com"},
                        new String[] {"EMAIL", " pad@example.com"},
                        new String[] {"EMAIL", "no-at-sign"},
                        new String[] {"PHONE", "0912345678"},
                        new String[] {"PHONE", "+0912345678"},
                        new String[] {"PHONE", "+123456"},
                        new String[] {"PHONE", "+1234567890123456"},
                        new String[] {"PHONE", "+886 912345678"},
                        new String[] {"MEMBER_ID", " padded"},
                        new String[] {"MEMBER_ID", "padded "},
                        new String[] {"MEMBER_ID", ""},
                        new String[] {"DEVICE_ID", "d".repeat(201)},
                        new String[] {"FAX", "123"});
        for (String[] b : bad) {
            assertThatThrownBy(() -> insertIdentifier(b[0], b[1], "P1"))
                    .as("%s '%s' 應被 CHECK 擋下", b[0], b[1])
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
