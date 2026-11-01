# 派工單 trace-customer-name

- **類型**：fix
- **負責**：frontend
- **目標**：甲方驗收回饋（2026-11-02）：軌跡頁麵包屑「客戶 / 這位客戶 / 旅程進度」與副標「客戶：未提供姓名」沒有意義，看不出是哪位客戶；改成顯示可辨識的客戶標示（以姓名優先，D-082）
- **背景**：截圖 `C:/Users/Marcus/AppData/Local/Temp/claude/D--workplace-CustomerDataPlatform/924dc974-dfe2-4e67-94b6-e97e6a580b8b/images/7.png`。目前姓名只取軌跡快照的 profile_snapshot.name，多數資料快照為空物件，退回「這位客戶」「未提供姓名」；甲方環境姓名已設為不遮罩（D-082），客戶頁可顯示姓名。取代作廢的 trace-customer-label（派工單欄位解析錯誤），qa 沿用其測試 commit 16f77c0
- **設計依據**：`docs/ui/enrollment-trace.md`（麵包屑、畫面 1）；契約 `docs/contracts/profiles.md`（客戶單筆查詢回傳 ProfileDetail，含已遮罩的屬性與識別碼）；D-014、D-021、D-080、D-082
- **相依**：無
- **負責檔案**：
  - `web/src/pages/TracePage.tsx`
  - `web/src/pages/trace/`
  - `web/tests/unit/trace/`
- **禁區**：不改 API、契約、後端；不改客戶頁、列表；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/trace-customer-name/`
  - `web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx`
  - `web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`
- **驗收條件**：
  - [ ] 客戶標示取得順序：1. 軌跡快照姓名；2. 客戶資料的姓名屬性（未遮罩時）；3. 第一個可顯示的識別碼（照後端遮罩後的值）；4. 都沒有才用「未具名客戶」；麵包屑中段與副標用同一個標示、中段連到客戶頁；主要畫面不出現客戶 ID；客戶資料取得失敗時頁面照常、退回第 4 步
  - [ ] 上列驗收測試全綠；`make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-customer-name.json`

## 執行資訊（ae task 產生）

- Issue：#183
- 分支：`fix/trace-customer-name`（從 develop 開出）
- 工作目錄：`.worktrees\trace-customer-name`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
