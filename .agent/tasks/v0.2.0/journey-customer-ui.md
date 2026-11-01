# 派工單 journey-customer-ui

- **類型**：feat
- **負責**：frontend
- **目標**：旅程列表與唯讀旅程頁、最近 Enrollment 瀏覽、客戶搜尋→客戶頁→圖形軌跡（直線）
- **背景**：即 DESIGN-03 派工表的 FE-03（驗收為 QA-05a）。後端（BE-07）尚未實作，一律對契約 mock。D-062 唯讀旅程頁（標頭、版本切換、節點圖，不疊軌跡）、D-048 完全比對且單筆直進客戶頁、D-050 列表不顯示人數、D-030 瀏覽清單。新相依 `@xyflow/react` 12.12.0、`@dagrejs/dagre` 3.1.1（D-009，DESIGN-03 做法 #6）。驗收測試由 qa 在本分支先寫好，不可改。
- **設計依據**：`docs/contracts/journeys.md` §1、§2；`docs/contracts/profiles.md` §1、§3；`docs/contracts/journey-engine.md` §4（含 `/enrollments` 瀏覽、§4.4 遮罩、§4.5 圖形軌跡推導）；`docs/design/DESIGN-03.md` 做法 #6；`docs/ui/journey-platform.md`（旅程列表、旅程頁、客戶搜尋、客戶頁、圖形軌跡直線；狀態、互動、圖形與遮罩、窄螢幕與無障礙）；設計稿 `docs/ui/journey-platform/index.html`；`web/src/styles/tokens.css`
- **相依**：app-shell-login（已合併）；後端為 mock
- **負責檔案**：
  - `web/src/`（`web/src/styles/tokens.css` 除外）
  - `web/package.json`、`package-lock.json`（只加上述兩個套件）
- **禁區**：`web/src/styles/tokens.css`；`web/tests/acceptance/`；`api/`、`docs/`；其他新套件（需要先回報）；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/journey-customer-ui/`
- **驗收條件**：
  - [ ] 旅程列表：欄位依 JourneySummary（名稱、狀態、版本、最後更新、最近發佈），不顯示人數；空、骨架、錯誤附 `request_id` 與重試、cursor 載入更多
  - [ ] 旅程頁：標頭＋版本下拉＋節點圖（直線、dagre 左到右自動排版）；切到舊版顯示「不是最新版本」；404「找不到這個旅程」
  - [ ] 客戶搜尋：網址同步 `?q=`；單筆直進客戶頁並顯示比對方式（`matched_by`）；多筆表格最多 20；空字串 400 文案；查無列出可能原因
  - [ ] 客戶頁：左基本資料（識別碼、屬性、遮罩徽章）右參加過的旅程；標題規則依 UI 規格；未知 `IdentifierType` 顯示原值（DESIGN-04 §5）；區塊錯誤分開處理
  - [ ] 圖形軌跡（直線）：節點三態、逐步紀錄 `<ol>`＋`aria-current="step"`；定義取得失敗退回逐步紀錄；遮罩只依後端標記
  - [ ] 最近 Enrollment 瀏覽（`q` 省略）可依狀態、旅程篩選
  - [ ] 窄螢幕與無障礙依 UI 規格；驗收測試全綠；既有前端測試全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/journey-customer-ui.json`

## 執行資訊（task.py 產生）

- Issue：#95
- 分支：`feat/journey-customer-ui`（從 develop 開出）
- 工作目錄：`.worktrees\journey-customer-ui`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-21

## 路由（architect 釐清，詳見 `reports/journey-customer-ui.routes.md`，以該檔為準）

- `/` 維持舊精確查詢頁；旅程列表 `/journeys`、旅程頁 `/journeys/:journeyId?version=N`、最近 Enrollment 瀏覽 `/enrollments`（不收 `q`）、客戶搜尋 `/customers?q=`、客戶頁 `/customers/:profileId`、圖形軌跡 `/enrollments/:enrollmentId`（取代舊詳情頁）；導覽「旅程」→ `/journeys`、「客戶」→ `/customers`
- 不改寫任何既有驗收測試；圖形軌跡頁須涵蓋舊詳情頁內容，讓既有測試照樣通過（routes.md §4 的限制清單）
- qa：lock 前把 `fixtures.tsx` 第 27–30 行錯誤的註解改正（斷言不變）
- worker：`/` 與 `/enrollments` 互相以頁內連結連通，文字先用「最近的 Enrollment」「依 ID 查詢」
