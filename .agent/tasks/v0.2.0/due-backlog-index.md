# 派工單 due-backlog-index

- **類型**：fix
- **負責**：backend
- **目標**：D-077：修正大量 Enrollment／outbox 同時到期時推進迴圈與 relay 的 O(N²)：V7_2 以原名重建 `ix_enrollment_wake`、`ix_effect_outbox_due`，各補第二排序欄；`lockDue`、`claimDue` 的 SQL 抽成 public 常數供驗收測試 EXPLAIN
- **背景**：壓測（relay-perf-bench）：1 萬筆同時到期清空 300 秒、10 萬筆 1 小時只清 18,944 筆；設計 `docs/design/due-backlog.md`（PR #131，甲方已核可；合併前以 `.worktrees/due-backlog-design/docs/design/due-backlog.md` 為準）以 EXPLAIN 證實原因是索引只含第一排序欄
- **設計依據**：`docs/design/due-backlog.md` 做法 #1～#6（DDL 草案在 #2）、「驗收」、「風險」、「自行決定」表；spec 07 §4、§8、§10、§12
- **相依**：無
- **負責檔案**：
  - `api/src/main/resources/db/migration/V7_2__due_queue_tiebreak_index.sql`（新；檔頭附 down 的做法）
  - `api/src/main/java/com/cdp/persistence/JdbcStateStore.java`、`api/src/main/java/com/cdp/persistence/JdbcOutboxStore.java`（只把 `lockDue`、`claimDue` 的 SQL 抽成 public 常數 `LOCK_DUE_SQL`、`CLAIM_DUE_SQL`，查詢文字不變）
  - `docs/spec/07-database-schema.md`（§4、§8、§10、§12 的索引定義）
- **禁區**：查詢文字、排序、`FOR UPDATE SKIP LOCKED` 不改；不新增索引（同名重建，不並存）；不用 `CONCURRENTLY`；V8 保留給 v0.3.0；`api/src/test/java/com/cdp/acceptance/` 由 qa 負責，worker 不改；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/due-backlog-index/`（設計「驗收」4 項：索引定義、2 萬筆並列時計畫無 Sort 且索引掃描列數 ≤ limit、並列時回傳順序與過濾不變、7.1 → 7.2 升級既有資料不變；Java 套件名照慣例去連字號 `duebacklogindex`；索引定義比對 PG 回傳格式，寫法照 `SchemaV5CatalogAcceptanceTest`；SQL 參數是具名參數 `:now`、`:limit`，EXPLAIN 時要能代入；不設時間上限）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] 後端全部測試通過：`docker compose --env-file .env -f .worktrees/due-backlog-index/compose.yaml run --rm test`
  - [ ] 既有驗收測試不需改寫（設計「受影響的既有驗收測試：無」；若實際有矛盾，停下來回報）
- **驗證指令**：`docker compose --env-file .env -f .worktrees/due-backlog-index/compose.yaml run --rm test`
- **回報檔**：`.agent/reports/due-backlog-index.json`

## 執行資訊（ae task 產生）

- Issue：#135
- 分支：`fix/due-backlog-index`（從 develop 開出）
- 工作目錄：`.worktrees\due-backlog-index`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
