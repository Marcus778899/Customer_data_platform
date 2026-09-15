package com.cdp.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V2 核心資料表的驗收測試（docs/spec/07-database-schema.md §9）。
 *
 * <p>測的是「schema 擋不擋得住」，不是「程式寫得對不對」——每一條都對應規格裡一個
 * 「靠自律會漏、必須由資料庫保證」的不變量。用真的 Postgres 跑，因為 migration 用了
 * JSONB、partial index、CHECK 運算式等 Postgres 專屬語法，換任何嵌入式資料庫都等於沒測。
 */
@Testcontainers
class CoreTablesMigrationTest {

    /** SQLState：唯一鍵衝突。 */
    private static final String UNIQUE_VIOLATION = "23505";

    /** SQLState：CHECK 約束違反。 */
    private static final String CHECK_VIOLATION = "23514";

    /** SQLState：非空約束違反。 */
    private static final String NOT_NULL_VIOLATION = "23502";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        // 驗收 1：在乾淨資料庫上從 V1 跑到 V2 無錯。
        // V1 的 CREATE ROLE ... BYPASSRLS 需要 superuser，Testcontainers 的預設帳號符合。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE journey_definition, enrollment_record, node_trace, event");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // ---------------------------------------------------------------- V1

    @Test
    @DisplayName("V1：journey_engine role 存在，且帶 BYPASSRLS")
    void engineRoleExists() throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT rolbypassrls, rolcanlogin FROM pg_roles WHERE rolname = 'journey_engine'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("journey_engine role 應該存在").isTrue();
                assertThat(rs.getBoolean("rolbypassrls")).as("必須帶 BYPASSRLS，否則計時器掃描一則都推不動").isTrue();
                assertThat(rs.getBoolean("rolcanlogin")).as("必須 NOLOGIN，只能被 SET ROLE 切換").isFalse();
            }
        }
    }

    // ------------------------------------------------- journey_definition

    @Test
    @DisplayName("journey_definition：同一 journey_id 的 v1 與 v2 可並存")
    void versionsCoexist() throws SQLException {
        insertJourney("J1", 1, "ARCHIVED");
        insertJourney("J1", 2, "ACTIVE");

        try (Connection c = connect();
                PreparedStatement ps =
                        c.prepareStatement("SELECT version FROM journey_definition WHERE journey_id = 'J1' ORDER BY version")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
        // 五萬人卡在 v1 等待節點時，v1 的定義必須還查得到（spec 03 §1）
    }

    @Test
    @DisplayName("journey_definition：同一 (journey_id, version) 不可重複")
    void duplicateVersionRejected() throws SQLException {
        insertJourney("J1", 1, "ACTIVE");

        assertThatThrownBy(() -> insertJourney("J1", 1, "ACTIVE"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
    }

    // ------------------------------------------------- enrollment_record

    @Test
    @DisplayName("G3：同一 dedup_key 併發插入 5 次，只有 1 次成功，其餘撞唯一約束")
    void concurrentDedupAllowsExactlyOne() throws Exception {
        int threads = 5;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        // enrollment_id 各自不同，dedup_key 相同——模擬同一 cart_id 同毫秒觸發 5 次
                        insertEnrollment("E" + n, "T1", "J1", "P1", "ACTIVE", "cart-9527", null);
                        inserted.incrementAndGet();
                    } catch (SQLException e) {
                        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                            conflicted.incrementAndGet();
                        } else {
                            unexpected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("併發插入應在 30 秒內結束").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).as("不應出現唯一鍵衝突以外的錯誤").isZero();
        assertThat(inserted.get()).as("只能有一筆寫進去").isEqualTo(1);
        assertThat(conflicted.get()).as("其餘四筆應撞 uq_enrollment_dedup").isEqualTo(4);
        // spec 02 §6：不可用「先查再插」判斷重複——那有競態。以資料庫約束為準。
    }

    @Test
    @DisplayName("G3：dedup_key 為 null 時不受唯一約束（COOLDOWN / UNLIMITED 模式）")
    void nullDedupKeyIsNotConstrained() throws SQLException {
        insertEnrollment("E1", "T1", "J1", "P1", "ACTIVE", null, null);
        insertEnrollment("E2", "T1", "J1", "P1", "ACTIVE", null, null);

        assertThat(countEnrollments()).isEqualTo(2);
    }

    @Test
    @DisplayName("CHECK：delivery_history 第 51 筆被擋下")
    void deliveryHistoryCapEnforced() {
        assertThatThrownBy(() -> insertEnrollmentWithJson("E1", jsonArray(51), "{}"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @Test
    @DisplayName("CHECK：delivery_history 剛好 50 筆可以寫入")
    void deliveryHistoryAtCapAccepted() throws SQLException {
        insertEnrollmentWithJson("E1", jsonArray(50), "{}");

        assertThat(countEnrollments()).isEqualTo(1);
    }

    @Test
    @DisplayName("CHECK：variables 超過 32 KB 被擋下")
    void variablesSizeCapEnforced() {
        assertThatThrownBy(() -> insertEnrollmentWithJson("E1", "[]", jsonObjectLargerThan(32768)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @Test
    @DisplayName("索引：lockDue 的查詢走 ix_enrollment_wake，不是 Seq Scan")
    void lockDueUsesPartialIndex() throws SQLException {
        seedForPlannerTest();

        String plan = explain(
                """
                SELECT * FROM enrollment_record
                 WHERE status = 'WAITING' AND next_wake_at <= now()
                 ORDER BY next_wake_at
                 LIMIT 100
                 FOR UPDATE SKIP LOCKED
                """);

        assertThat(plan)
                .as("推進迴圈每秒都在跑，走 Seq Scan 等於整個引擎的吞吐上限被這一條查詢決定\n實際計畫：\n%s", plan)
                .contains("ix_enrollment_wake");
    }

    // --------------------------------------------------------- node_trace

    @Test
    @DisplayName("node_trace：decision_reason 為 null 時插入失敗")
    void decisionReasonIsMandatory() {
        assertThatThrownBy(() -> insertTrace("E1", 1L, null))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(NOT_NULL_VIOLATION));
        // spec 02 §8：沒有這個，客服每次都要找工程師撈日誌。
    }

    @Test
    @DisplayName("node_trace：同一 enrollment 的 seq 不可重複（冪等鍵的第三段）")
    void traceSeqIsUniquePerEnrollment() throws SQLException {
        insertTrace("E1", 1L, "會員等級=銀卡，不在 [金卡, 白金卡] 中");

        assertThatThrownBy(() -> insertTrace("E1", 1L, "重複的 seq"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
    }

    // -------------------------------------------------------------- event

    @Test
    @DisplayName("event：重複的 (tenant_id, event_id) 撞主鍵")
    void duplicateEventRejected() throws SQLException {
        insertEvent("EV1", "T1", "order_completed");

        assertThatThrownBy(() -> insertEvent("EV1", "T1", "order_completed"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
        // spec 01 §7：重複的 event_id 直接丟棄並回 200（不是錯誤，是正常重試）
    }

    @Test
    @DisplayName("event：不同租戶的同一 event_id 互不干擾")
    void eventIdIsScopedByTenant() throws SQLException {
        insertEvent("EV1", "T1", "order_completed");
        insertEvent("EV1", "T2", "order_completed");

        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM event")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2);
        }
    }

    // ----------------------------------------------------------- helpers

    private static void insertJourney(String journeyId, int version, String status) throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO journey_definition
                          (journey_id, tenant_id, version, name, status, settings, entry_node, nodes, created_at)
                        VALUES (?, 'T1', ?, '棄單挽回', ?, '{}'::jsonb, 'start_1', '[]'::jsonb, now())
                        """)) {
            ps.setString(1, journeyId);
            ps.setInt(2, version);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    private static void insertEnrollment(
            String enrollmentId,
            String tenantId,
            String journeyId,
            String profileId,
            String status,
            String dedupKey,
            Instant nextWakeAt)
            throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO enrollment_record
                          (enrollment_id, tenant_id, journey_id, journey_version, profile_id,
                           status, current_node_id, next_wake_at, trigger_snapshot, dedup_key,
                           enrolled_at, updated_at, terminated_at)
                        VALUES (?, ?, ?, 1, ?, ?, 'node_1', ?, '{}'::jsonb, ?, now(), now(), ?)
                        """)) {
            ps.setString(1, enrollmentId);
            ps.setString(2, tenantId);
            ps.setString(3, journeyId);
            ps.setString(4, profileId);
            ps.setString(5, status);
            ps.setTimestamp(6, nextWakeAt == null ? null : Timestamp.from(nextWakeAt));
            ps.setString(7, dedupKey);
            // 終態才有 terminated_at；WAITING / ACTIVE 一律 null，ix_enrollment_journey_active 靠它篩選
            ps.setTimestamp(8, "COMPLETED".equals(status) ? Timestamp.from(Instant.now()) : null);
            ps.executeUpdate();
        }
    }

    private static void insertEnrollmentWithJson(String enrollmentId, String deliveryHistory, String variables)
            throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO enrollment_record
                          (enrollment_id, tenant_id, journey_id, journey_version, profile_id,
                           status, current_node_id, trigger_snapshot, variables, delivery_history,
                           enrolled_at, updated_at)
                        VALUES (?, 'T1', 'J1', 1, 'P1', 'ACTIVE', 'node_1', '{}'::jsonb,
                                ?::jsonb, ?::jsonb, now(), now())
                        """)) {
            ps.setString(1, enrollmentId);
            ps.setString(2, variables);
            ps.setString(3, deliveryHistory);
            ps.executeUpdate();
        }
    }

    private static void insertTrace(String enrollmentId, long seq, String decisionReason) throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO node_trace
                          (enrollment_id, seq, tenant_id, node_id, node_type,
                           entered_at, decision_reason, evaluated)
                        VALUES (?, ?, 'T1', 'cond_1', 'ConditionNode', now(), ?, '{}'::jsonb)
                        """)) {
            ps.setString(1, enrollmentId);
            ps.setLong(2, seq);
            ps.setString(3, decisionReason);
            ps.executeUpdate();
        }
    }

    private static void insertEvent(String eventId, String tenantId, String name) throws SQLException {
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(
                        """
                        INSERT INTO event
                          (event_id, tenant_id, source_type, name, occurred_at, received_at, identity)
                        VALUES (?, ?, 'CUSTOMER_BEHAVIOR', ?, now(), now(), '{"profile_id":"P1"}'::jsonb)
                        """)) {
            ps.setString(1, eventId);
            ps.setString(2, tenantId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static long countEnrollments() throws SQLException {
        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM enrollment_record")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * planner 只在表夠大、統計資訊夠新時才會選索引。小表走 Seq Scan 是正確決策，
     * 所以要先把資料量與分布做到接近真實：絕大多數是終態，少數在等待。
     */
    private static void seedForPlannerTest() throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    """
                    INSERT INTO enrollment_record
                      (enrollment_id, tenant_id, journey_id, journey_version, profile_id,
                       status, current_node_id, next_wake_at, trigger_snapshot,
                       enrolled_at, updated_at, terminated_at)
                    VALUES (?, 'T1', 'J1', 1, ?, ?, 'node_1', ?, '{}'::jsonb, now(), now(), ?)
                    """)) {
                Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
                for (int i = 0; i < 10_000; i++) {
                    boolean waiting = i % 200 == 0; // 50 筆在等待，其餘已結束
                    ps.setString(1, "E" + i);
                    ps.setString(2, "P" + i);
                    ps.setString(3, waiting ? "WAITING" : "COMPLETED");
                    ps.setTimestamp(4, waiting ? Timestamp.from(past) : null);
                    ps.setTimestamp(5, waiting ? null : Timestamp.from(past));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            try (Statement s = c.createStatement()) {
                s.execute("ANALYZE enrollment_record");
            }
        }
    }

    private static String explain(String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    /** 產生指定長度的 JSON 陣列，用來壓 delivery_history 的 50 筆上限。 */
    private static String jsonArray(int elements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"nodeId\":\"n").append(i).append("\",\"status\":\"SENT\"}");
        }
        return sb.append(']').toString();
    }

    /** 產生序列化後大於指定位元組數的 JSON 物件，用來壓 variables 的 32 KB 上限。 */
    private static String jsonObjectLargerThan(int bytes) {
        StringBuilder sb = new StringBuilder("{\"payload\":\"");
        sb.append("x".repeat(bytes + 1024));
        return sb.append("\"}").toString();
    }
}
