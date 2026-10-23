package com.cdp.acceptance.userauth;

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
 * user-auth-api 驗收：spec 07 §12 V6 只建 {@code app_user}，不動既有表。獨立的 PostgreSQL 容器、直接用 Flyway，升級目標固定 6，
 * 之後新增的 migration 不影響本測試。
 */
class SchemaV6AppUserAcceptanceTest {

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

    private Set<String> columns() {
        return new TreeSet<>(jdbc.queryForList(
                "select table_name || '.' || column_name || ':' || data_type || ':' || is_nullable"
                        + " from information_schema.columns where table_schema = 'public'",
                String.class));
    }

    private Set<String> indexes() {
        return new TreeSet<>(jdbc.queryForList(
                "select indexdef from pg_indexes where schemaname = 'public'", String.class));
    }

    @Test
    void v6_creates_only_app_user_and_leaves_existing_tables_untouched() {
        migrate("5");
        Set<String> colsBefore = columns();
        Set<String> idxBefore = indexes();
        List<String> tablesBefore = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'"
                        + " and table_name <> 'flyway_schema_history' order by 1",
                String.class);
        assertThat(tablesBefore).doesNotContain("app_user");

        migrate("6");

        List<String> tablesAfter = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'"
                        + " and table_name <> 'flyway_schema_history' order by 1",
                String.class);
        assertThat(tablesAfter).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(tablesBefore.stream(), java.util.stream.Stream.of("app_user")).toList());

        Set<String> colsAfter = columns();
        Set<String> nonUser = new TreeSet<>(colsAfter);
        nonUser.removeIf(c -> c.startsWith("app_user."));
        assertThat(nonUser).isEqualTo(colsBefore);

        Set<String> idxAfter = indexes();
        Set<String> idxNonUser = new TreeSet<>(idxAfter);
        idxNonUser.removeIf(d -> d.contains(" ON public.app_user "));
        assertThat(idxNonUser).isEqualTo(idxBefore);

        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version = '6' and success", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void app_user_columns_and_constraints() {
        migrate("6");
        List<String> cols = jdbc.queryForList(
                "select column_name || ':' || is_nullable from information_schema.columns"
                        + " where table_schema = 'public' and table_name = 'app_user' order by 1",
                String.class);
        assertThat(cols)
                .containsExactlyInAnyOrder(
                        "user_id:NO",
                        "email:NO",
                        "display_name:NO",
                        "password_hash:NO",
                        "role:NO",
                        "status:NO",
                        "created_at:NO",
                        "updated_at:NO");
        assertThat(jdbc.queryForList(
                        "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid"
                                + " and a.attnum = any(i.indkey) where i.indrelid = 'app_user'::regclass"
                                + " and i.indisprimary",
                        String.class))
                .containsExactly("user_id");
        assertThat(indexes())
                .anySatisfy(d -> assertThat(d).contains("UNIQUE").contains("uq_app_user_email").contains("(email)"));
    }

    private void insert(String id, String email, String role, String status) {
        jdbc.update(
                "insert into app_user(user_id, email, display_name, password_hash, role, status, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?,?)",
                id,
                email,
                "n",
                "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz0123456",
                role,
                status,
                T0,
                T0);
    }

    @Test
    void app_user_rejects_bad_role_status_and_duplicate_email() {
        migrate("6");
        insert("01JD0USER0000000000000001", "a@example.com", "ADMIN", "ACTIVE");
        insert("01JD0USER0000000000000002", "b@example.com", "VIEWER", "DISABLED");
        insert("01JD0USER0000000000000003", "c@example.com", "EDITOR", "ACTIVE");
        assertThatThrownBy(() -> insert("01JD0USER0000000000000004", "d@example.com", "ROOT", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("01JD0USER0000000000000005", "e@example.com", "VIEWER", "DELETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("01JD0USER0000000000000006", "a@example.com", "VIEWER", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("01JD0USER0000000000000001", "f@example.com", "VIEWER", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
