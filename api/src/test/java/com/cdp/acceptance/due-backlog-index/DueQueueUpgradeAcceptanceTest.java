package com.cdp.acceptance.duebacklogindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** due-backlog-index 驗收 #4：Flyway 先到 7.1 並塞 WAITING／PENDING 列，再到 7.2，資料不變、索引換成新定義。 */
class DueQueueUpgradeAcceptanceTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-10-30T02:00:00Z"));

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

    private String indexdef(String name) {
        return jdbc.queryForObject("select indexdef from pg_indexes where indexname = ?", String.class, name);
    }

    private Set<String> indexNames() {
        return new TreeSet<>(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'", String.class));
    }

    @Test
    void upgrade71To72_keepsRows_andReplacesBothIndexes() {
        migrate("7.1");
        assertThat(indexdef("ix_enrollment_wake")).contains("(next_wake_at)");
        assertThat(indexdef("ix_effect_outbox_due")).contains("(available_at)");

        for (int i = 1; i <= 5; i++) {
            jdbc.update(
                    "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                            + " current_node_id, wake_type, next_wake_at, trigger_snapshot, enrolled_at, updated_at)"
                            + " values (?,?,1,?,?,'wait_1','TIMER',?,'{\"k\":1}'::jsonb,?,?)",
                    "E" + i, "J1", "P" + i, i == 5 ? "COMPLETED" : "WAITING", T0, T0, T0);
            jdbc.update(
                    "insert into effect_outbox(idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                            + " visit_seq, effect_index, effect_type, payload, status, available_at, created_at)"
                            + " values (?,?,?,1,'n1',1,0,'SEND','{\"a\":2}'::jsonb,?,?,?)",
                    "K" + i, "E" + i, "J1", i == 5 ? "PUBLISHED" : "PENDING", T0, T0);
        }
        List<Map<String, Object>> enrBefore = jdbc.queryForList("select * from enrollment_record order by enrollment_id");
        List<Map<String, Object>> outBefore = jdbc.queryForList("select * from effect_outbox order by idempotency_key");
        Set<String> namesBefore = indexNames();

        migrate("7.2");

        assertThat(jdbc.queryForList("select * from enrollment_record order by enrollment_id")).isEqualTo(enrBefore);
        assertThat(jdbc.queryForList("select * from effect_outbox order by idempotency_key")).isEqualTo(outBefore);
        assertThat(indexNames()).isEqualTo(namesBefore); // 同名重建，不多不少
        assertThat(indexdef("ix_enrollment_wake"))
                .isEqualTo(
                        "CREATE INDEX ix_enrollment_wake ON public.enrollment_record USING btree"
                                + " (next_wake_at, enrollment_id)"
                                + " WHERE ((status = 'WAITING'::text) AND (next_wake_at IS NOT NULL))");
        assertThat(indexdef("ix_effect_outbox_due"))
                .isEqualTo(
                        "CREATE INDEX ix_effect_outbox_due ON public.effect_outbox USING btree"
                                + " (available_at, idempotency_key) WHERE (status = 'PENDING'::text)");
        assertThat(jdbc.queryForObject("select count(*) from pg_index where not indisvalid", Integer.class)).isZero();
    }
}
