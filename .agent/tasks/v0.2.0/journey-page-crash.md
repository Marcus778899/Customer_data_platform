# 派工單 journey-page-crash

- **類型**：fix
- **負責**：frontend
- **目標**：甲方驗收回饋（2026-11-01）：點進旅程 `01M56YZJMN3BCYQ73E9Y941E4C` 的旅程頁出現白畫面（整頁崩潰），修正並確保任何旅程資料都不會讓頁面整頁白掉
- **背景**：甲方在本機（後端 `make api-run`、前端 `make web-dev`，有多批 demo-seed 與壓測遺留資料）從旅程列表點進該旅程後白畫面。uat 時示範旅程正常，推測是特定資料形狀（例：壓測或舊版本建立的旅程定義、版本、layout 為 null、節點型別或欄位缺漏）讓 `JourneyPage`／圖元件丟例外。根因未知，先重現
- **設計依據**：`docs/ui/journey-platform.md`（唯讀旅程頁、狀態表的錯誤狀態）；契約 `docs/contracts/journeys.md`；D-062
- **相依**：無
- **負責檔案**：
  - `web/src/pages/JourneyPage.tsx`、`web/src/graph/`（`GraphSection.tsx`、`FlowGraph.tsx`、`model.ts`）
  - 必要時 `web/src/App.tsx`（加頁面層級的錯誤邊界，顯示規格的錯誤狀態而不是白畫面）
  - `web/src/` 底下對應的單元測試
- **禁區**：不改 API、契約與後端；若根因在後端回傳不符契約，停下來回報（附實際回應），由 manager 另開單；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/journey-page-crash/`（qa 先重現：取得該旅程實際的 API 回應形狀（本機後端在跑，可經 make 匯出的變數呼叫 API，不讀 .env），以該形狀與其他契約允許的邊界形狀（layout null、空節點、未知節點型別、多版本）餵旅程頁，斷言不白畫面、顯示內容或規格的錯誤狀態）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] 根因寫進回報（哪個資料形狀、哪一行丟例外）
  - [ ] 頁面層級錯誤邊界：任何渲染例外都顯示規格的錯誤狀態，不再白畫面
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/journey-page-crash.json`

## 執行資訊（ae task 產生）

- Issue：#159
- 分支：`fix/journey-page-crash`（從 develop 開出）
- 工作目錄：`.worktrees\journey-page-crash`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
