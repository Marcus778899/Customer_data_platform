# 派工單 customer-table-fit

- **類型**：fix
- **負責**：frontend
- **通道**：fast
- **目標**：v0.2.0 uat 第 2 輪 R-F1（major）：客戶頁 1280px「參加過的旅程」表格最右欄「結束時間」被卡片邊緣切掉；順帶修同頁面的小問題 R-F2、R-F4
- **背景**：`docs/uat/v0.2.0.md` 第 2 輪 R-F1、R-F2、R-F4；截圖 `docs/uat/v0.2.0/r2-customer-1280.png`。甲方回饋第 4 點要求 1280px 表格不擠壓、無橫向捲動，PR #175 修正後從逐字換行變成被切掉
- **設計依據**：`docs/ui/journey-platform.md`（客戶頁、窄螢幕）、`docs/ui/enrollment-trace.md`；D-016、D-079
- **相依**：無
- **負責檔案**：
  - `web/src/styles/app.css`（只動客戶頁 `table.enr` 段與客戶頁小標間距）
  - `web/src/pages/CustomerPage.tsx`
  - `web/src/pages/JourneyPage.tsx`（只改 R-F2 那個連結文字）
  - `web/tests/unit/plain-list/`（補測試）
- **禁區**：不改 API、契約、`.trace-page` 段落、其他頁面；不可讀 .env
- **驗收測試**：無（fast；worker 自己補測試）
- **驗收條件**：
  - [ ] R-F1：1280px（以及 1440、500、375）客戶頁「參加過的旅程」所有欄位完整可見，不被切掉、不逐字換行、頁面無橫向捲動（例如縮短欄寬、時間欄不換行、步驟欄允許正常換行，或必要時卡片內可橫向捲動並有提示——擇一並在回報說明）
  - [ ] R-F2：旅程頁右上「這個旅程的 Enrollment」改為「旅程進度」用詞（D-079）
  - [ ] R-F4：客戶頁畫面不再出現「D-016」等內部編號；「資料時間」與「識別碼」小標之間有正常間距
  - [ ] 補單元測試；`make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/customer-table-fit.json`

## 執行資訊（ae task 產生）

- Issue：#177
- 分支：`fix/customer-table-fit`（從 develop 開出）
- 工作目錄：`.worktrees\customer-table-fit`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
