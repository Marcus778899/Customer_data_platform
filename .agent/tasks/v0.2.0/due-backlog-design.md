# 派工單 due-backlog-design

- **類型**：docs
- **負責**：architect
- **目標**：D-077：確認「大量 Enrollment／outbox 同時到期時推進迴圈清空 O(N²)」的原因，出修法、驗收方式與實作派工單內容
- **背景**：壓測（relay-perf-bench，PR #129，報告在 `.worktrees/relay-perf-bench/docs/perf/v0.2.0.md`、原始輸出 `last-run.md`，合併前以那兩份為準）：1 萬筆同時到期清空 300 秒；10 萬筆 1 小時只清 18,944 筆。backend 初判 `JdbcStateStore.lockDue`、`JdbcOutboxStore.claimDue` 在第一排序欄並列時每次讀過全部並列列。relay 1 萬 4 秒、10 萬 131 秒；派發約 210／秒、consumer 約 115／秒；無子交易溢位
- **設計依據**：`docs/design/DESIGN-03.md`「relay 與迴圈」表；spec 07 §12；`api/src/main/resources/db/migration/`（V8 保留給 v0.3.0 BE-08，新 migration 編號照 V7_1 的做法）；D-038、D-047、D-077
- **相依**：無
- **負責檔案**：
  - `docs/design/due-backlog.md`（新）
- **禁區**：只寫設計，不改程式、migration、測試；不可讀 .env
- **驗收測試**：無（docs）
- **驗收條件**：
  - [ ] 用 `last-run.md` 的 `EXPLAIN (ANALYZE, BUFFERS)` 證實或推翻 O(N²) 的原因；派發（`lockUndispatched`）與 consumer 的速度是否也需要處理，一起判斷
  - [ ] 修法（索引、查詢改寫或兩者）：寫出 DDL 草案、migration 編號、對既有查詢與寫入的影響（寫入放大、鎖）、上線時建索引的方式（是否 `CONCURRENTLY`）與回滾
  - [ ] 驗收方式：qa 要寫的驗收測試（例：查詢計畫不再隨並列數線性增加，或小規模時間上限），以及壓測重跑 1 萬／10 萬的對照目標（D-038 不設流量目標，但要證明不再是 O(N²)）
  - [ ] 派工順序：實作單名稱、負責、負責檔案、受影響的既有驗收測試；「待決定」只列 CLAUDE.md 決策分級四類，其他寫「自行決定」表
- **驗證指令**：無（docs）
- **回報檔**：`.agent/reports/due-backlog-design.json`

## 執行資訊（ae task 產生）

- Issue：#130
- 分支：`docs/due-backlog-design`（從 develop 開出）
- 工作目錄：`.worktrees\due-backlog-design`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
