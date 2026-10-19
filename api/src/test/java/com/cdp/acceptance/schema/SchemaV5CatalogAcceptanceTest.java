package com.cdp.acceptance.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cdp.acceptance.engine.EngineTestSupport;

/**
 * QA-09：V5 移除 tenant_id 後的系統目錄驗收（spec 07 §12「V5」、D-063）。
 * 以應用程式啟動後（Flyway 套到最新）的資料庫為準。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class SchemaV5CatalogAcceptanceTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void publicSchema_hasNoTenantIdColumn() {
        List<String> hits =
                jdbc.queryForList(
                        "select table_name || '.' || column_name from information_schema.columns"
                                + " where table_schema = 'public' and column_name = 'tenant_id'"
                                + " order by 1",
                        String.class);
        assertThat(hits).isEmpty();
    }

    @Test
    void publicSchema_noIndexMentionsTenantId() {
        assertThat(
                        jdbc.queryForList(
                                "select indexname from pg_indexes where schemaname = 'public'"
                                        + " and indexdef like '%tenant_id%'",
                                String.class))
                .isEmpty();
    }

    @Test
    void event_primaryKey_isEventIdOnly() {
        List<String> cols =
                jdbc.queryForList(
                        "select a.attname from pg_index i"
                                + " join pg_attribute a on a.attrelid = i.indrelid"
                                + " and a.attnum = any(i.indkey)"
                                + " where i.indrelid = 'public.event'::regclass and i.indisprimary"
                                + " order by a.attname",
                        String.class);
        assertThat(cols).containsExactly("event_id");
        assertThat(
                        jdbc.queryForObject(
                                "select pg_get_indexdef(i.indexrelid) from pg_index i"
                                        + " where i.indrelid = 'public.event'::regclass and i.indisprimary",
                                String.class))
                .contains("event_pkey")
                .contains("(event_id)");
    }

    @Test
    void rebuiltIndexes_matchSpecIndexdef() {
        Map<String, String> expected =
                Map.of(
                        "ix_event_name_time",
                        "CREATE INDEX ix_event_name_time ON public.event USING btree (name, occurred_at DESC)",
                        "uq_enrollment_dedup",
                        "CREATE UNIQUE INDEX uq_enrollment_dedup ON public.enrollment_record USING btree"
                                + " (journey_id, profile_id, dedup_key) WHERE (dedup_key IS NOT NULL)",
                        "ix_enrollment_wake_event",
                        "CREATE INDEX ix_enrollment_wake_event ON public.enrollment_record USING btree"
                                + " (profile_id, wake_event_name) WHERE ((status = 'WAITING'::text)"
                                + " AND (wake_event_name IS NOT NULL))",
                        "ix_enrollment_journey_active",
                        "CREATE INDEX ix_enrollment_journey_active ON public.enrollment_record USING btree"
                                + " (journey_id) WHERE (terminated_at IS NULL)",
                        "ix_enrollment_profile",
                        "CREATE INDEX ix_enrollment_profile ON public.enrollment_record USING btree"
                                + " (profile_id, enrolled_at DESC, enrollment_id DESC)",
                        "ix_effect_outbox_enrollment",
                        "CREATE INDEX ix_effect_outbox_enrollment ON public.effect_outbox USING btree"
                                + " (enrollment_id)",
                        "ix_delivery_task_enrollment",
                        "CREATE INDEX ix_delivery_task_enrollment ON public.delivery_task USING btree"
                                + " (enrollment_id, attempted_at DESC, idempotency_key DESC)");
        assertThat(expected).hasSize(7);
        for (Map.Entry<String, String> e : expected.entrySet()) {
            List<String> defs =
                    jdbc.queryForList(
                            "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
                            String.class,
                            e.getKey());
            assertThat(defs).as(e.getKey()).containsExactly(e.getValue());
        }
    }

    @Test
    void untouchedIndexes_stillExist() {
        List<String> names =
                jdbc.queryForList(
                        "select indexname from pg_indexes where schemaname = 'public'", String.class);
        assertThat(names)
                .contains("ix_enrollment_wake", "ix_event_dispatch_queue", "ix_effect_outbox_due");
    }
}
