PASS 818977b

# QA due-backlog-design（docs，PR #131）

## 範圍
- `git diff --stat develop...HEAD`：只有 `docs/design/due-backlog.md`（+60），工作樹乾淨。符合負責檔案，沒動程式、migration、測試。

## make check
- 在 `.worktrees/due-backlog-design` 實際執行，輸出結尾：web 16 files / 196 tests passed，`check passed`，exit 0。

## EXPLAIN 數字對照（`.worktrees/relay-perf-bench/docs/perf/last-run.md`，commit 71b0677）
- lockDue 1 萬：Execution 22.515 ms、Incremental Sort、Presorted Key next_wake_at、Pre-sorted Groups 1、Index Scan rows=10000。設計寫 22.5：一致
- lockDue 10 萬：197.983 ms、external merge Disk 37552kB、rows=100000。設計寫 198、37 MB：一致
- claimDue 1 萬 20.498 ms、10 萬 162.248 ms（Incremental Sort，Index Scan rows=10000／100000）。設計寫 20.5／162：一致。外層 1 萬 Seq Scan + Hash Semi Join、10 萬 Nested Loop + effect_outbox_pkey：一致
- lockUndispatched：0.053 ms／0.050 ms，只走 ix_event_dispatch_queue，rows=1：一致
- 壓測表：advance 33.3／5.3 筆/s、300.1 s／3601 s（逾時 18,944 筆來自派工單背景）、relay 2453.0／764.8、dispatch 208.6／208.9、consumer 113.4／116.6；一致
- 推算 1/22.5ms ≈ 44/s、1/198ms ≈ 5/s 與實測 33／5.3 同量級，推論成立（O(N²) 證實）

## 驗收測試方案可行性（`acceptance/due-backlog-index/`，4 項）
- 目錄形式可行：既有 `acceptance/profile-search-api/`、`user-auth-api/` 等也用連字號目錄。
- #1 讀 `pg_indexes.indexdef`：可行（既有 SchemaV5Catalog 同做法）。
- #2 EXPLAIN JSON 檢查無 Sort、Index Scan rows ≤ limit、Temp Written = 0：可行；正式 SQL 目前是行內字串且用具名參數（`:now`、`:limit`），所以設計 #6 抽常數是必要的，實作單要讓測試能代入參數（見 minor 2）。
- #3 並列順序與過濾：可行（`lockDue`／`claimDue` 皆為公開 store 方法）。
- #4 Flyway 先到 7.1 再到 7.2：可行，既有 SchemaV6／V7 測試已有 `migrate(target)` 的做法；版本排序 7 < 7.1 < 7.2 正確。

## 「受影響的既有驗收測試：無」
- grep `ix_enrollment_wake`、`ix_effect_outbox_due`、`indexdef`、`next_wake_at`、`available_at`：
  - `SchemaV5CatalogAcceptanceTest::untouchedIndexes_stillExist` 只查名稱，同名重建後仍成立；`rebuiltIndexes_matchSpecIndexdef` 的 7 個索引不含這兩個。
  - `SchemaV5Upgrade`（target 4／5，`max(version::int)` 只在 ≤5 時執行）、`SchemaV6AppUser`、`SchemaV7ProfileStore`（target 6／7）固定 Flyway target，不經過 V7_2。
  - 全 migration 的測試沒有斷言歷史表最新版本為 7.1。
- 判斷站得住。

## 硬編碼設定
- 設計文件沒有 port、網址、token；只引用 `PERF_SIZES`、`PERF_TIMEOUT`、`OUTBOX_RELAY_BATCH_SIZE` 變數名。

## 非阻擋的小問題（minor，建議實作單注意，不影響判定）
1. 設計寫測試套件 `com.cdp.acceptance.duebacklog`；既有慣例是目錄去連字號（`profile-search-api` → `profilesearchapi`），對應應為 `duebacklogindex`。qa 寫測試時照慣例即可。
2. 測試 #1「等於 #2」要比對 PG 回傳格式（`... USING btree (next_wake_at, enrollment_id) WHERE ((status = 'WAITING'::text) AND (next_wake_at IS NOT NULL))`），不是 DDL 原文；測試要照 SchemaV5Catalog 的寫法。
3. 以後若有測試在 7.x 版之後用 `max(version::int)`，會因 `7.1`／`7.2` 轉型失敗；目前沒有此情形。
