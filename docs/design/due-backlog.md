# 設計：大量同時到期的佇列查詢（due-backlog-design，D-077）
- 契約：無（不改 API、不改查詢語意）；設定：無新變數。依據：PR #129 的 `docs/perf/v0.2.0.md`、`last-run.md`；`JdbcStateStore.lockDue`、`JdbcOutboxStore.claimDue`、`AdvanceLoop.advanceNext`（每筆一交易呼叫 `lockDue(now, 失敗數+1)`）、`OutboxRelay`（每批 `claimDue(…, OUTBOX_RELAY_BATCH_SIZE)`）；`V1` `ix_enrollment_wake`、`V2` `ix_effect_outbox_due`、`V3` `ix_event_dispatch_queue`；spec 07 §4「索引與約束」、§8、§10、§12；DESIGN-03「relay 與迴圈」
## 原因（`last-run.md` 的 `EXPLAIN (ANALYZE, BUFFERS)`）
| 查詢 | 證據（N=1 萬／10 萬） | 判定 |
|---|---|---|
| `lockDue`（limit 1） | Index Scan 讀出 10000／100000 列 → `Incremental Sort`，`Presorted Key: next_wake_at`、`Pre-sorted Groups: 1`（全部並列成一組）；10 萬時 external merge 37 MB；22.5／198 ms。1/22.5 ms ≈ 44/s、1/198 ms ≈ 5/s，對上實測 33／5.3 筆/s：每筆時間幾乎全花在 `lockDue` | **證實**：單次成本 ∝ 並列數，每筆一次 → O(N²) |
| `claimDue`（limit 100） | 同上：讀 10000／100000 列再排序，20.5／162 ms；10 萬時 relay 2800 → 700/s | **證實**，攤到每筆是 1/100，屬輕微；外層 UPDATE 在 1 萬時走 Seq Scan＋Hash Semi Join、10 萬時改 Nested Loop＋pkey，是表小時的成本選擇，不處理 |
| `lockUndispatched`（limit 1） | `ix_event_dispatch_queue` 兩欄都在索引內，LIMIT 提前結束，0.05 ms 不隨 N；派發 1 萬／10 萬都是 209/s | O(N)，瓶頸是每事件一交易的 `WalSync`；**不處理** |
| consumer | 1 萬／10 萬都是 113～117/s；每則一交易、`application.yml` 未設 listener 並行度 | O(N)、不隨積壓退化、不是到期查詢；**不屬 D-077**，見待決定 |
## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 兩個佇列索引補上 ORDER BY 第二欄，同名重建；查詢、ORDER BY、`FOR UPDATE SKIP LOCKED` 一字不改 | 兩個排序欄都在索引內，LIMIT 讀到 limit 列就停（同 `ix_event_dispatch_queue` 已驗證）；保留 tie-break 的決定性。不改查詢（只按第一欄排序）是因為並列時的處理順序會變得不確定，測試與追查都變難 |
| 2 | 新 migration `V7_2__due_queue_tiebreak_index.sql`（V8 保留給 BE-08，同 V7_1 做法）；內容：`DROP INDEX ix_enrollment_wake; CREATE INDEX ix_enrollment_wake ON enrollment_record (next_wake_at, enrollment_id) WHERE status = 'WAITING' AND next_wake_at IS NOT NULL; DROP INDEX ix_effect_outbox_due; CREATE INDEX ix_effect_outbox_due ON effect_outbox (available_at, idempotency_key) WHERE status = 'PENDING';`；檔頭註解寫 down（同名重建回單欄 `(next_wake_at)`、`(available_at)`，條件不變） | Flyway 一個交易，失敗整支回滾，不會留下沒有索引的狀態 |
| 3 | 不走 expand／contract、不並存兩份索引 | 程式不以名稱引用索引；舊程式同一條 SQL 照樣用新索引（第一欄相同），新舊並存沒問題；兩份並存只會多一份寫入。同名讓 spec 07 與 `SchemaV5CatalogAcceptanceTest::untouchedIndexes_stillExist` 不用改 |
| 4 | 不用 `CREATE INDEX CONCURRENTLY`，在 Flyway 交易內建 | 鎖：`DROP INDEX` 對該表取 ACCESS EXCLUSIVE 到 commit，建索引時間 ∝ partial 條件內的列（WAITING 有到期時間／PENDING）。Flyway 在應用程式啟動、迴圈與 API 開始前執行；v0.2.0 環境還沒有資料來源串接（D-052、D-059），這兩類列少。`CONCURRENTLY` 不能在交易內、失敗會留 INVALID 索引、要拆多支 migration 並改名，代價大於好處。收件區（v0.4.0）後的大表索引變更再改用 `CONCURRENTLY`（寫進 spec 07 §8） |
| 5 | 寫入影響：索引數不變；`enrollment_id`、`idempotency_key` 是 PK、不會更新，HOT 判定不變（`next_wake_at`、`available_at` 本來就在索引裡）；每個索引項目多一個 text key，partial 索引只含待處理列；執行期鎖不變（仍是 `FOR UPDATE SKIP LOCKED` 鎖列） | — |
| 6 | 兩條 SQL 抽成 `public static final String`：`JdbcStateStore.LOCK_DUE_SQL`、`JdbcOutboxStore.CLAIM_DUE_SQL`（內容一字不改，方法改用常數） | 驗收測試 EXPLAIN 正式 SQL，不是複本（`tools/perf/run.py` 目前是複本） |
| 7 | 回滾：程式回滾不用動 DB（同一條 SQL）；要退回舊索引就照 #2 的 down 另開 migration；沒有資料變動 | — |
## 威脅與對策（無對外介面）
| 資產 | 威脅 | 對策 |
|---|---|---|
| 到期列處理「不漏、不重、順序固定」 | 索引改動讓查詢挑錯列或順序改變 | SQL 不變（#1、#6）；驗收 #3 驗並列時的順序與過濾 |
| 上線時的可用性 | migration 鎖表過久、中途失敗 | #4 的時機與列數；交易內失敗整支回滾（#2）；驗收 #4 驗既有資料升級 |
## 驗收方式
qa（`due-backlog-index`）寫在 `api/src/test/java/com/cdp/acceptance/due-backlog-index/`（套件 `com.cdp.acceptance.duebacklog`，需 DB，compose `test` 服務與 CI 會跑）。不用時間上限：CI runner 時間不穩，#2 直接驗「不隨並列數讀列」。
| # | 測試 | 判定 |
|---|---|---|
| 1 | 套到最新後讀 `pg_indexes.indexdef` | 兩個索引的定義等於 #2（欄位與 WHERE） |
| 2 | 塞 20000 筆同一 `next_wake_at` 的 WAITING、20000 筆同一 `available_at` 的 PENDING，`ANALYZE`；在會回滾的交易內 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` + `LOCK_DUE_SQL`（limit 1）、`CLAIM_DUE_SQL`（limit 100） | 計畫樹沒有 `Sort`／`Incremental Sort` 節點；對應索引的 Index Scan `Actual Rows` ≤ limit；`Temp Written Blocks` = 0 |
| 3 | 並列時呼叫 `StateStore.lockDue(now, 1)`、`OutboxStore.claimDue(now, lease, 100)` | 回傳 `enrollment_id`／`idempotency_key` 最小的列；`next_wake_at`／`available_at` > now 或狀態不符的列不回傳 |
| 4 | Flyway 先到 `7.1` 並塞 WAITING、PENDING 列，再到 `7.2` | 列數與內容不變、索引同 #1 |
壓測重跑（`due-backlog-perf`，`make perf`，`PERF_SIZES=10000,100000`、`PERF_TIMEOUT=3600`）的對照目標，只證明不再是 O(N²)，不是流量目標（D-038）：
| 項目 | 修正前 | 目標 |
|---|---|---|
| `lockDue`、`claimDue` 的 EXPLAIN（1 萬、10 萬） | 22.5／198 ms、20.5／162 ms，Incremental Sort | 無排序節點、Index Scan 列數 ≤ limit、無 temp；10 萬的 Execution Time < 1 萬的 3 倍 |
| advance | 1 萬 300 s；10 萬逾時（18,944 筆） | 10 萬在時限內清空；平均筆/s（10 萬）≥ 0.7 ×（1 萬）；清空時間比 ≤ 15 |
| relay | 2453 → 765 筆/s | 平均筆/s（10 萬）≥ 0.7 ×（1 萬） |
| dispatch、consumer | 209、115 筆/s | 只記錄；與修正前差 > 20% 要在報告說明 |
## 受影響的既有驗收測試：無
grep `acceptance/` 的 `ix_enrollment_wake`、`ix_effect_outbox_due`、`indexdef`：`SchemaV5CatalogAcceptanceTest::untouchedIndexes_stillExist` 只查名稱（同名重建，不矛盾）；`SchemaV5Upgrade`、`SchemaV6AppUser`、`SchemaV7ProfileStore` 用固定 Flyway target，不受 V7_2 影響。
## 檔案分工與派工順序
| 單 | 負責 | 負責檔案 | 相依 | 驗證 |
|---|---|---|---|---|
| `due-backlog-index`（＋qa） | backend | `api/src/main/resources/db/migration/V7_2__due_queue_tiebreak_index.sql`（新）、`api/src/main/java/com/cdp/persistence/JdbcStateStore.java`、`JdbcOutboxStore.java`（只做 #6）、`docs/spec/07-database-schema.md`（§4 索引表 `ix_enrollment_wake` 列、§10 `effect_outbox` 索引欄、§8 台帳加 V7_2 與 #4 的 CONCURRENTLY 原則、§12 加「V7_2」小節） | 無 | `make check`；`docker compose --env-file .env -f .worktrees/due-backlog-index/compose.yaml run --rm test` |
| `due-backlog-perf` | backend | `docs/perf/v0.2.0.md`（加「修正後對照」節）、`docs/perf/last-run.md`（重新產生）；`tools/perf/run.py` 的 EXPLAIN 改用 #2 不需要（SQL 未變） | `due-backlog-index`、`relay-perf-bench`（PR #129）都合併 | `make perf`，輸出對上方目標表 |
## 自行決定
| 項目 | 決定 |
|---|---|
| 修法 | 補索引第二欄，不改查詢（#1） |
| 同名重建、不並存、不用 CONCURRENTLY | #3、#4 |
| 驗收不設時間上限；壓測門檻 0.7 倍、清空時間比 15、EXPLAIN 3 倍 | 線性時 10 倍 N 約 10 倍時間，留 50% 餘裕；修正前比值遠超（10 萬推估 > 2 小時） |
## 待甲方決定／需要調查
- 待決定：consumer（約 115/s，10 萬筆在 relay 清空後還要 14 分鐘）與派發（約 210/s）的吞吐調校要不要納入 v0.2.0（① 範圍）。建議：不納入，排進 backlog；兩者不隨積壓退化，D-038 也沒有流量目標
- 需調查：無
## 風險
| 風險 | 對策 |
|---|---|
| 已處理列的舊索引項目留在索引前端（等 vacuum），每次掃描都要跳過 | PG 第一次碰到就標 LP_DEAD，之後只跳過不讀 heap；`due-backlog-perf` 的「平均筆/s ≥ 0.7 倍」會抓到；沒達標再評估 autovacuum 參數 |
| 修正後 advance 改受每筆一交易的 WAL 同步限制（推估與派發同量級） | 不是 O(N²)；要再快就是改 DESIGN-03「每筆一個交易」，屬上方待決定的同一類調校 |
