# 派工單 CHORE-02

- **類型**：chore
- **負責**：architect
- **目標**：FIX-03 前置：V3 migration（事件派發重試）與設計補充
- **背景**：D-024 定案：事件派發退避重試 N 次後放棄、標為已派發並記錄錯誤，該事件不建立 Enrollment。DESIGN-01「後續實作項」的引擎穩健列需要 V3 `event` 欄位，由 architect 先出。
  另併入：BE-02 review 裁定的 DB 例外分流表補進 DESIGN-01 做法 #4；FIX-03 的實作範圍與檔案分工（含 BE-02 review 的 relay 穩健項：首次 publish 失敗結束批次、available_at 退避、mandatory＋ReturnedMessage、DB 重排退避、移除全域 missingQueuesFatal 的前置）。
- **設計依據**：docs/design/DESIGN-01.md；docs/spec/07-database-schema.md；PR #24 review 留言（`gh pr view 24 --comments`）
- **相依**：無
- **負責檔案**：
  - `api/src/main/resources/db/migration/V3__*.sql`
  - `docs/design/DESIGN-01.md`
  - `docs/spec/07-database-schema.md`
- **禁區**：其他 `api/` 程式與測試、`web/`、`scripts/`、`docs/decisions.md`、README.md、BOARD.md；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] V3 migration 新增事件派發重試所需欄位（嘗試次數、下次可派發時間、最後錯誤等）與必要索引，V1→V3 在 PG 18 套用成功，既有測試全過
  - [ ] spec 07 同步記載新欄位與語意；重試上限與退避參數若需設定，列出變數名稱（不寫值）
  - [ ] DESIGN-01 做法 #4 補 DB 例外分流表；寫出 FIX-03 的範圍、負責檔案與驗收要點，讓 manager 可直接開單
- **驗證指令**：`make check`
- **回報檔**：`reports/CHORE-02.json`

## 執行資訊（task.py 產生）

- Issue：#26
- 分支：`chore/chore-02`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-02`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
