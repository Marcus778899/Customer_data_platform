APPROVE 1134da0

## 檢查範圍（PR #138，`gh pr diff 138`；對照派工單、設計 `.worktrees/due-backlog-design/docs/design/due-backlog.md`、spec 07）

| 項目 | 結果 |
|---|---|
| V7_2 DDL 與設計 #2 | 一致：`DROP INDEX` 後同名重建 `(next_wake_at, enrollment_id)`、`(available_at, idempotency_key)`；WHERE 與 `V1__core_tables.sql:54`、`V2__engine_pipeline.sql:35` 逐字相同；無 `CONCURRENTLY`；未新增其他索引；版本 7.2，未佔 V8 |
| down | 檔頭註解寫同名重建回單欄、條件不變（設計 #2、#7） |
| `LOCK_DUE_SQL`、`CLAIM_DUE_SQL`（設計 #6） | 字串逐字搬出、`public static final`，方法改用常數；ORDER BY、`FOR UPDATE SKIP LOCKED`、參數綁定不變；常數不含 secret |
| spec 07 | §4 索引表、§10 `effect_outbox` 索引欄、§8 台帳 V7_2 列、§12 新增「V7_2」小節；另把 §12 relay 搶列的 `ORDER BY available_at` 補成與實際 SQL 相符的 `available_at, idempotency_key`（文件修正，正確） |
| 負責檔案 | worker commit 1134da0 只動 4 個負責檔；兩個驗收測試檔是 qa 的 commit 7ae2f15 |
| 寫死設定、安全 | 無新設定、無對外介面；SQL 仍為參數綁定 |
| 驗收測試對照設計「驗收方式」 | #1 `indexdef` 全字比對；#2 20000 筆並列、回滾交易內 EXPLAIN 正式常數，驗無 Sort／Incremental Sort、Index Scan 列數 ≤ limit、temp 0；#3 並列順序（`enrollment_id`／`idempotency_key` 最小）與過濾（未到期、狀態不符、null、等於 now）；#4 7.1 → 7.2 列內容、索引名集合不變、新定義、無 INVALID 索引。四項都有實際測到 |
| 套件名 | 測試用 `com.cdp.acceptance.duebacklogindex`，設計寫 `duebacklog`；派工單指定去連字號的 `duebacklogindex`，以派工單為準，不算問題 |
| CI | Check pass、Changes pass；Security fail：分支從 12ebb43 開出，早於 minimatch 修正（7910c2d），與本單變更無關，sync develop 後應會消失 |

## qa 備註：CONCURRENTLY 原則的位置

設計 #4／檔案分工寫「§8 台帳加 V7_2 與 CONCURRENTLY 原則」，實際寫在 §12「V7_2」小節最後一點（`docs/spec/07-database-schema.md:489`），§8 台帳列指向 §12「V7_2」。

判定：**可接受，不擋合併**。內容與設計 #4 一致、只寫一次，從 §8 台帳可以連到。不過「收件區（v0.4.0）之後的大表索引變更改用 `CONCURRENTLY`」是之後所有 migration 都要遵守的原則，放在 V7_2 小節裡，之後寫 migration 的人只看 §8「鐵則」會漏掉。建議（非阻擋）：下次動 spec 07 的單（例如 v0.4.0 收件區設計）把這條移到 §8「鐵則」，§12 改成引用。

## 未執行

- 依指示未跑含容器的測試；後端 397 筆全綠以 qa 證據（`.agent/reports/due-backlog-index.qa.md`）為準
