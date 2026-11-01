# 派工單 trace-customer-label

- **類型**：fix
- **負責**：frontend
- **目標**：甲方驗收回饋（2026-11-02）：軌跡頁麵包屑「客戶 / 這位客戶 / 旅程進度」與副標「客戶：未提供姓名」沒有意義，看不出是哪位客戶；改成顯示可辨識的客戶標示
- **背景**：截圖 `C:/Users/Marcus/AppData/Local/Temp/claude/D--workplace-CustomerDataPlatform/924dc974-dfe2-4e67-94b6-e97e6a580b8b/images/7.png`。目前姓名只取 `trigger_snapshot.profile_snapshot.name`，示範與多數資料 `profile_snapshot` 為 `{}`，退回「這位客戶」「未提供姓名」；依 D-080 又不能直接顯示 ID。軌跡頁其餘安排甲方已認可
- **設計依據**：`docs/ui/enrollment-trace.md`（麵包屑、畫面 1）；契約 `docs/contracts/profiles.md`（`GET /api/v1/profiles/{profile_id}` → `ProfileDetail`，含姓名屬性與識別碼，已依白名單遮罩）；D-014、D-021（遮罩）、D-080
- **相依**：無
- **負責檔案**：
  - `web/src/pages/TracePage.tsx`、`web/src/pages/trace/`
  - `web/tests/unit/trace/`
- **禁區**：不改 API、契約、後端；不改客戶頁、列表；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/trace-customer-label/`（客戶標示的取得順序：1. 軌跡的 `profile_snapshot` 姓名；2. 另取 `GET /profiles/{profile_id}` 的姓名屬性（未遮罩時）；3. 該客戶第一個可顯示的識別碼（email、手機、會員編號等，照後端遮罩後的值顯示，例「c***@example.com」）；4. 都沒有才用「未具名客戶」；麵包屑中段與副標都用同一個標示、麵包屑中段連到客戶頁；主要畫面仍不出現客戶 ID（D-080）；`/profiles` 取得失敗或 404 時頁面照常顯示並退回第 4 步，不影響軌跡內容；遮罩值不可被還原）
  - 受影響的既有驗收測試：`web/tests/acceptance/trace-plain-rewrite/summary.test.tsx`、`web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx`、`web/tests/acceptance/trace-plain-rewrite/fixtures.tsx`、`web/tests/acceptance/trace-page/trace-page.test.tsx`、`web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`（只改麵包屑／副標客戶標示與新增的 `/profiles` 請求相關斷言；qa「A」依新規格改寫後一起 lock）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-customer-label.json`

## 執行資訊（ae task 產生）

- Issue：#182
- 分支：`fix/trace-customer-label`（從 develop 開出）
- 工作目錄：`.worktrees\trace-customer-label`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
