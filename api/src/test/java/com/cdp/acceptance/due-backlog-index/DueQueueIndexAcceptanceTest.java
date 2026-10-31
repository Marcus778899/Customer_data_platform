package com.cdp.acceptance.duebacklogindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cdp.acceptance.engine.EngineTestSupport;
import com.cdp.delivery.OutboxEntry;
import com.cdp.delivery.OutboxStore;
import com.cdp.engine.StateStore;
import com.cdp.engine.model.EnrollmentState;
import com.cdp.persistence.JdbcOutboxStore;
import com.cdp.persistence.JdbcStateStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * due-backlog-index 驗收（D-077，設計 docs/design/due-backlog.md「驗收方式」#1～#3）：
 * 索引定義、並列到期時正式 SQL 的計畫不排序且不隨並列數讀列、並列時回傳順序與過濾不變。不設時間上限。
 */
@SpringBootTest(
        properties = {
            "cdp.engine.poll-interval=PT1H",
            "cdp.relay.poll-interval=PT1H",
            "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@ActiveProfiles("test")
@Import(EngineTestSupport.class)
class DueQueueIndexAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(3600);
    private static final Instant FUTURE = NOW.plusSeconds(3600);
    private static final int BULK = 20000;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired StateStore stateStore;
    @Autowired OutboxStore outboxStore;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from effect_outbox");
        jdbc.update("delete from enrollment_record");
    }

    // ---- #1 索引定義（PG 回傳格式）----

    @Test
    void indexDefinitions_haveTiebreakSecondColumn_andSameName() {
        assertThat(indexdef("ix_enrollment_wake"))
                .isEqualTo(
                        "CREATE INDEX ix_enrollment_wake ON public.enrollment_record USING btree"
                                + " (next_wake_at, enrollment_id)"
                                + " WHERE ((status = 'WAITING'::text) AND (next_wake_at IS NOT NULL))");
        assertThat(indexdef("ix_effect_outbox_due"))
                .isEqualTo(
                        "CREATE INDEX ix_effect_outbox_due ON public.effect_outbox USING btree"
                                + " (available_at, idempotency_key) WHERE (status = 'PENDING'::text)");
    }

    private String indexdef(String name) {
        return jdbc.queryForObject("select indexdef from pg_indexes where indexname = ?", String.class, name);
    }

    // ---- #2 計畫 ----

    @Test
    void lockDueSql_withManyTies_noSort_indexRowsWithinLimit_noTempFiles() {
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                        + " current_node_id, wake_type, next_wake_at, trigger_snapshot, enrolled_at, updated_at)"
                        + " select 'E' || lpad(i::text, 6, '0'), 'J1', 1, 'P' || i, 'WAITING', 'wait_1', 'TIMER',"
                        + " ?, '{}'::jsonb, ?, ? from generate_series(1, " + BULK + ") i",
                ts(PAST),
                ts(PAST),
                ts(PAST));
        jdbc.execute("analyze enrollment_record");

        JsonNode plan = explain(bind(JdbcStateStore.LOCK_DUE_SQL, NOW, null, 1));

        assertThat(nodeTypes(plan)).as("計畫節點").noneMatch(t -> t.contains("Sort"));
        assertThat(indexScanRows(plan, "ix_enrollment_wake")).isPositive().isLessThanOrEqualTo(1);
        assertThat(tempWritten(plan)).isZero();
    }

    @Test
    void claimDueSql_withManyTies_noSort_indexRowsWithinLimit_noTempFiles() {
        jdbc.update(
                "insert into effect_outbox(idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                        + " visit_seq, effect_index, effect_type, payload, status, available_at, created_at)"
                        + " select 'K' || lpad(i::text, 6, '0'), 'E' || i, 'J1', 1, 'n1', 1, 0, 'SEND', '{}'::jsonb,"
                        + " 'PENDING', ?, ? from generate_series(1, " + BULK + ") i",
                ts(PAST),
                ts(PAST));
        jdbc.execute("analyze effect_outbox");

        JsonNode plan = explain(bind(JdbcOutboxStore.CLAIM_DUE_SQL, NOW, NOW.plusSeconds(60), 100));

        assertThat(nodeTypes(plan)).as("計畫節點").noneMatch(t -> t.contains("Sort"));
        assertThat(indexScanRows(plan, "ix_effect_outbox_due")).isPositive().isLessThanOrEqualTo(100);
        assertThat(tempWritten(plan)).isZero();
    }

    /** 具名參數代入字面值（EXPLAIN 不能用 JdbcClient 的具名參數）。 */
    private static String bind(String sql, Instant now, Instant lease, int limit) {
        String s = sql.replace(":now", "'" + now + "'::timestamptz").replace(":limit", String.valueOf(limit));
        if (lease != null) {
            s = s.replace(":lease", "'" + lease + "'::timestamptz");
        }
        assertThat(s).as("SQL 仍有未代入的具名參數").doesNotContainPattern("(?<!:):[a-z]+");
        return s;
    }

    /** 在會回滾的交易內 EXPLAIN ANALYZE（claimDue 是 UPDATE，不可留下改動）。 */
    private JsonNode explain(String sql) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return tx.execute(
                status -> {
                    status.setRollbackOnly();
                    String json = jdbc.queryForObject("explain (analyze, buffers, format json) " + sql, String.class);
                    return JsonMapper.builder().build().readTree(json).get(0).get("Plan");
                });
    }

    private static List<String> nodeTypes(JsonNode plan) {
        List<String> out = new ArrayList<>();
        walk(plan, n -> out.add(n.get("Node Type").toString().replace("\"", "")));
        return out;
    }

    private static long indexScanRows(JsonNode plan, String indexName) {
        long[] rows = {0};
        walk(
                plan,
                n -> {
                    JsonNode idx = n.get("Index Name");
                    if (idx != null && idx.toString().replace("\"", "").equals(indexName)) {
                        rows[0] += n.get("Actual Rows").longValue() * Math.max(1, n.get("Actual Loops").longValue());
                    }
                });
        return rows[0];
    }

    private static long tempWritten(JsonNode plan) {
        long[] sum = {0};
        walk(plan, n -> sum[0] += n.has("Temp Written Blocks") ? n.get("Temp Written Blocks").longValue() : 0);
        return sum[0];
    }

    private static void walk(JsonNode node, Consumer<JsonNode> f) {
        f.accept(node);
        JsonNode kids = node.get("Plans");
        if (kids != null) {
            for (JsonNode k : kids) {
                walk(k, f);
            }
        }
    }

    // ---- #3 並列時的順序與過濾 ----

    @Test
    void lockDue_ties_returnsSmallestEnrollmentIds_inOrder_andFiltersOthers() {
        // 刻意亂序插入，全部同一 next_wake_at
        for (String id : List.of("E05", "E02", "E09", "E01", "E07", "E03")) {
            insertEnrollment(id, "WAITING", PAST);
        }
        insertEnrollment("E00-future", "WAITING", FUTURE); // 未到期
        insertEnrollment("E00-done", "COMPLETED", PAST); // 狀態不符
        insertEnrollment("E00-null", "WAITING", null); // 沒有喚醒時間
        insertEnrollment("E00-exact", "WAITING", NOW); // 剛好等於 now：到期，但時間較晚排最後

        assertThat(ids(stateStore.lockDue(NOW, 1))).containsExactly("E01");
        assertThat(ids(stateStore.lockDue(NOW, 3))).containsExactly("E01", "E02", "E03");

        // next_wake_at 較早者先出；並列者依 enrollment_id
        insertEnrollment("E99", "WAITING", PAST.minusSeconds(10));
        assertThat(ids(stateStore.lockDue(NOW, 1))).containsExactly("E99");
        assertThat(ids(stateStore.lockDue(NOW, 100)))
                .containsExactly("E99", "E01", "E02", "E03", "E05", "E07", "E09", "E00-exact");
    }

    @Test
    void claimDue_ties_claimsSmallestKeys_andFiltersOthers() {
        Instant lease = NOW.plusSeconds(60);
        for (int i = 150; i >= 1; i--) { // 反向插入
            insertOutbox(String.format("K%03d", i), "PENDING", PAST);
        }
        insertOutbox("K000-future", "PENDING", FUTURE);
        insertOutbox("K000-published", "PUBLISHED", PAST);
        insertOutbox("K000-cancelled", "CANCELLED", PAST);

        List<OutboxEntry> claimed = outboxStore.claimDue(NOW, lease, 100);

        Set<String> expected = new TreeSet<>();
        for (int i = 1; i <= 100; i++) {
            expected.add(String.format("K%03d", i));
        }
        assertThat(claimed.stream().map(OutboxEntry::idempotencyKey).collect(Collectors.toSet())).isEqualTo(expected);
        assertThat(claimed).allMatch(e -> e.availableAt().equals(lease));
        assertThat(jdbc.queryForObject("select count(*) from effect_outbox where available_at = ?", Integer.class, ts(lease)))
                .isEqualTo(100);

        // 租約未到期，第二輪只領剩下 50 筆 K101..K150
        List<OutboxEntry> second = outboxStore.claimDue(NOW, lease, 100);
        assertThat(second).hasSize(50);
        assertThat(second.stream().map(OutboxEntry::idempotencyKey).sorted().toList().get(0)).isEqualTo("K101");
    }

    private static List<String> ids(List<EnrollmentState> list) {
        return list.stream().map(EnrollmentState::enrollmentId).toList();
    }

    private void insertEnrollment(String id, String status, Instant wakeAt) {
        jdbc.update(
                "insert into enrollment_record(enrollment_id, journey_id, journey_version, profile_id, status,"
                        + " current_node_id, wake_type, next_wake_at, trigger_snapshot, enrolled_at, updated_at)"
                        + " values (?,?,1,?,?,'wait_1','TIMER',?,'{}'::jsonb,?,?)",
                id,
                "J1",
                "P-" + id,
                status,
                wakeAt == null ? null : ts(wakeAt),
                ts(PAST),
                ts(PAST));
    }

    private void insertOutbox(String key, String status, Instant availableAt) {
        jdbc.update(
                "insert into effect_outbox(idempotency_key, enrollment_id, journey_id, journey_version, node_id,"
                        + " visit_seq, effect_index, effect_type, payload, status, available_at, created_at)"
                        + " values (?,?,?,1,'n1',1,0,'SEND','{}'::jsonb,?,?,?)",
                key,
                "E-" + key,
                "J1",
                status,
                ts(availableAt),
                ts(PAST));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
