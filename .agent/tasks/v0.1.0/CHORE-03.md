# 派工單 CHORE-03

- **類型**：chore
- **負責**：architect
- **目標**：v0.1.0 收尾：V4 收緊 event 派發約束、文件與契約對齊已合併行為
- **背景**：來源 PR #28、#30、#32 review。FIX-03a 已合併，所有派發都寫 `dispatch_outcome`；V3～FIX-03a 之間派發的列 outcome 為 NULL，V4 需先回填（同 V3 的 LATE／DISPATCHED 規則）再加雙向 CHECK。
  文件：DESIGN-01「FIX-03」派發重試列改為 `recordFailure(...)→int`＋`scheduleRetry(...)`；spec 07 §10 effect_type 改「明確對照（`JdbcStateStore.effectType`）」、§11「過渡」補 V4；契約 §1.2 `/auth/me` 補 403（FIX-04 行為）；V3 檔頭補手動反向步驟註解。
  另列出 v0.1.0 後剩下的後續項（APPEND_TRACE 明確 API、outcome enum、DispatchProperties 驗證等）建議歸屬，供 manager 決定放 v0.1.0 或之後。
- **設計依據**：docs/spec/07-database-schema.md §10、§11；docs/design/DESIGN-01.md；docs/contracts/journey-engine.md §1.2
- **相依**：無
- **負責檔案**：
  - `api/src/main/resources/db/migration/V4__*.sql`、`api/src/main/resources/db/migration/V3__event_dispatch_retry.sql`（只加檔頭註解）
  - `docs/design/DESIGN-01.md`、`docs/spec/07-database-schema.md`、`docs/contracts/journey-engine.md`
- **禁區**：其他 `api/` 程式與測試、`web/`、`scripts/`、`docs/decisions.md`、README.md、BOARD.md；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] V4：回填 outcome 為 NULL 的已派發列後，加 `(dispatch_outcome IS NULL) = (dispatched_at IS NULL)`；V1→V4 套用成功、既有測試全過
  - [ ] 上述文件與契約修改完成，且與已合併程式一致
  - [ ] 回報列出剩餘後續項與建議歸屬
- **驗證指令**：`make check`
- **回報檔**：`reports/CHORE-03.json`

## 執行資訊（task.py 產生）

- Issue：#34
- 分支：`chore/chore-03`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-03`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（manager）：接受 architect 的偏離：V3 不加檔頭註解（Flyway checksum 會讓已套用 V3 的資料庫驗證失敗），反向步驟改寫在 V4 檔頭。

## 追加 2026-10-18

- 追加 2（review changes）：spec 07 §8 台帳加 `V4 | V4__event_dispatch_outcome_strict.sql | §11：回填 dispatch_outcome，CHECK 改雙向`，RLS 改「V5 起（v0.2.0）」；§8「新增 V3、V4」改「新增下一版」；§9「V1～V3」「V1 跑到 V3」改 V4。一併採納非阻擋 1、2（做法 #2 反向步驟改為每支 migration 各寫自己的、V3 例外寫在 V4；§11 末句改為兩欄須同一 UPDATE 同時設值或同時清空）。
