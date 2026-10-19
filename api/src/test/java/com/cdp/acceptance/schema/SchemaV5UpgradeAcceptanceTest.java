package com.cdp.acceptance.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * QA-09：V4 灌資料後升到 V5（spec 07 §12「V5」、D-063）。
 * 獨立的 PostgreSQL 容器、直接用 Flyway；升級目標固定 5，之後新增的 migration 不影響本測試。
 */
class SchemaV5UpgradeAcceptanceTest {

    private static final Timestamp T0 = Timestamp.from(Instant.parse("2026-10-18T02:00:00Z"));
    private static final String[] TABLES = {
        "journey_definition",
        "enrollment_record",
        "node_trace",
        "event",
        "effect_outbox",
        "delivery_task",
        "mock_channel_message"
    };

    private PostgreSQLContainer pg;
    private JdbcTemplate jdbc;

    @BeforeEach
    void start() {
        pg = new PostgreSQLContainer("postgres:18-alpine");
        pg.start();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        migrate("4");
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

    private void seedEvent(String tenant, String eventId) {
        jdbc.update(
                "insert into event(event_id, tenant_id, source_type, name, occurred_at, received_at,"
                        + " identity) values (?,?,'CUSTOMER_BEHAVIOR','e',?,?,'{}'::jsonb)",
                eventId,
                tenant,
                T0,
                T0);
    }

    private void seedEnrollment(
            String tenant, String id, String journey, String profile, String dedup) {
        jdbc.update(
                "insert into enrollment_record(enrollment_id, tenant_id, journey_id, journey_version,"
                        + " profile_id, status, current_node_id, trigger_snapshot, dedup_key, enrolled_at,"
                        + " updated_at) values (?,?,?,1,?,'WAITING','wait_1','{}'::jsonb,?,?,?)",
                id,
                tenant,
                journey,
                profile,
                dedup,
                T0,
                T0);
    }

    /** 每張表灌資料（含兩個不同 tenant_id），回傳各表列數。 */
    private Map<String, Integer> seedV4() {
        for (String t : new String[] {"T1", "T2"}) {
            jdbc.update(
                    "insert into journey_definition(journey_id, tenant_id, version, name, status, settings,"
                            + " entry_node, nodes, created_at) values (?,?,1,'j','ACTIVE','{}'::jsonb,"
                            + "'start_1','[]'::jsonb,?)",
                    "jrn-" + t,
                    t,
                    T0);
            seedEvent(t, "evt-" + t);
            seedEvent(t, "evt-" + t + "-b");
            seedEnrollment(t, "enr-" + t + "-1", "jrn-" + t, "p1", "d1");
            seedEnrollment(t, "enr-" + t + "-2", "jrn-" + t, "p1", null);
            seedEnrollment(t, "enr-" + t + "-3", "jrn-" + t, "p1", null);
            jdbc.update(
                    "insert into node_trace(enrollment_id, seq, tenant_id, node_id, node_type, entered_at,"
                            + " decision_reason, evaluated) values (?,1,?,'n','StartNode',?,'r','{}'::jsonb)",
                    "enr-" + t + "-1",
                    t,
                    T0);
            jdbc.update(
                    "insert into effect_outbox(idempotency_key, tenant_id, enrollment_id, journey_id,"
                            + " journey_version, node_id, visit_seq, effect_index, effect_type, payload,"
                            + " status, available_at, created_at) values (?,?,?,?,1,'n',1,0,'SEND_MESSAGE',"
                            + "'{}'::jsonb,'PENDING',?,?)",
                    "ob-" + t,
                    t,
                    "enr-" + t + "-1",
                    "jrn-" + t,
                    T0,
                    T0);
            jdbc.update(
                    "insert into delivery_task(idempotency_key, tenant_id, enrollment_id, journey_id,"
                            + " journey_version, node_id, visit_seq, template_id, channel_id, channel_type,"
                            + " status, attempted_at, created_at, updated_at)"
                            + " values (?,?,?,?,1,'n',1,'tpl','ch','MOCK','PENDING',?,?,?)",
                    "dt-" + t,
                    t,
                    "enr-" + t + "-1",
                    "jrn-" + t,
                    T0,
                    T0,
                    T0);
            jdbc.update(
                    "insert into mock_channel_message(idempotency_key, tenant_id, enrollment_id,"
                            + " template_id, channel_id, variables, sent_at)"
                            + " values (?,?,?,'tpl','ch','{}'::jsonb,?)",
                    "mm-" + t,
                    t,
                    "enr-" + t + "-1",
                    T0);
        }
        return counts();
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : TABLES) {
            m.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class));
        }
        return m;
    }

    private int tenantColumns() {
        return jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public'"
                        + " and column_name = 'tenant_id'",
                Integer.class);
    }

    @Test
    void upgradeV4ToV5_keepsRowCounts_andDropsTenantId() {
        Map<String, Integer> before = seedV4();
        assertThat(before.values()).allMatch(n -> n >= 2);
        assertThat(tenantColumns()).isEqualTo(7);

        migrate("5");

        assertThat(counts()).isEqualTo(before);
        assertThat(tenantColumns()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select max(version::int) from flyway_schema_history where success",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void upgradeV4ToV5_primaryKeyAndDedupIndexStillRejectDuplicates() {
        seedV4();
        migrate("5");

        assertThat(tenantColumns()).isZero();

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into event(event_id, source_type, name, occurred_at,"
                                                + " received_at, identity)"
                                                + " values ('evt-T1','CUSTOMER_BEHAVIOR','e',?,?,'{}'::jsonb)",
                                        T0,
                                        T0))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into enrollment_record(enrollment_id, journey_id,"
                                                + " journey_version, profile_id, status, current_node_id,"
                                                + " trigger_snapshot, dedup_key, enrolled_at, updated_at)"
                                                + " values ('enr-dup','jrn-T1',1,'p1','WAITING','wait_1',"
                                                + "'{}'::jsonb,'d1',?,?)",
                                        T0,
                                        T0))
                .isInstanceOf(DataIntegrityViolationException.class);

        // dedup_key 為 NULL 不受限（partial unique index 的 WHERE 與 V1 相同）
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id,"
                        + " status, current_node_id, trigger_snapshot, dedup_key, enrolled_at, updated_at)"
                        + " values ('enr-null','jrn-T1',1,'p1','WAITING','wait_1','{}'::jsonb,null,?,?)",
                T0,
                T0);
    }

    @Test
    void upgradeV4ToV5_duplicateEventIdAcrossTenants_failsAndRollsBackWholeMigration() {
        seedEvent("T1", "evt-same");
        seedEvent("T2", "evt-same");
        seedEnrollment("T1", "enr-1", "jrn-1", "p1", "d1");
        Map<String, Integer> before = counts();

        assertThatThrownBy(() -> migrate("5"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V5__drop_tenant_id.sql");

        // 整支回滾：tenant_id 欄位、舊 PK、舊索引都還在，資料不變，歷史表停在 V4
        assertThat(counts()).isEqualTo(before);
        assertThat(tenantColumns()).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "select pg_get_indexdef(i.indexrelid) from pg_index i"
                                        + " where i.indrelid = 'public.event'::regclass and i.indisprimary",
                                String.class))
                .contains("(tenant_id, event_id)");
        assertThat(
                        jdbc.queryForObject(
                                "select indexdef from pg_indexes where indexname = 'ix_enrollment_profile'",
                                String.class))
                .contains("tenant_id");
        assertThat(
                        jdbc.queryForObject(
                                "select max(version::int) from flyway_schema_history where success",
                                Integer.class))
                .isEqualTo(4);
    }
}
