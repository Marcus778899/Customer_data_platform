# 派工單 trace-plain-page

- **類型**：feat
- **負責**：frontend
- **目標**：D-081（方案 A）：軌跡頁白話改版——首屏三問摘要卡、白話步驟時間軸、訊息發送紀錄、技術細節收進「進階資訊」，讓行銷、業務不需教育訓練就看得懂
- **背景**：甲方驗收時指出軌跡頁（例 `/enrollments/<id>`）只有工程師看得懂；設計 plain-ux-ui（PR #162）甲方已核可；術語與時間模組 plain-copy-glossary（PR #166）已合併
- **設計依據**：`docs/ui/enrollment-trace.md`（方案、畫面由上而下 1～5、摘要組句表、步驟標題、狀態與互動、麵包屑、窄螢幕與無障礙、自行決定表）；設計稿 `docs/ui/enrollment-trace/plain.html`（15 個狀態）；`web/src/plain/`、`web/src/format.ts` 的 `formatPlainTime(iso, now, { relative })`；D-016、D-079、D-080、D-081、D-014／D-021（遮罩）
- **相依**：plain-copy-glossary（已合併）；與 trace-plain-list 可平行
- **負責檔案**：
  - `web/src/pages/TracePage.tsx`（與其拆出的新元件，放 `web/src/pages/trace/`）
  - `web/src/styles/app.css`（只動軌跡頁段落）
  - `web/src/graph/GraphSection.tsx`（若規格要求調整軌跡頁上的圖或移入進階資訊）
  - 對應單元測試（`web/tests/unit/` 或 `web/src/` 旁）
- **禁區**：不改 API、契約；不改 `web/src/plain/`、`format.ts`（不夠用就停下回報）；列表、搜尋、客戶頁屬 trace-plain-list；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/trace-plain-page/`（依規格與設計稿：摘要卡三問在等待中／走完待送／已送出／發生錯誤／已中止各狀態的句子；時間軸步驟標題與狀態字、目前步驟 `aria-current="step"`；「接下來」取不到定義時降級；訊息紀錄與無資料說明；進階資訊預設收合且含 ID、UTC 原值等；主要畫面不出現 Enrollment、節點型別名、UTC 原字串、ID（D-079、D-080）；遮罩照舊；載入、404、錯誤狀態；麵包屑；重新整理與「更新於」）
  - 受影響的既有驗收測試：`web/tests/acceptance/trace-page/`、`web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`、`web/tests/acceptance/integration/`、`web/tests/acceptance/session-expired/`、`web/tests/acceptance/journey-customer-ui/narrow-a11y.test.tsx` 中涉及軌跡頁的部分（qa「A」依新規格改寫後一起 lock）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-plain-page.json`

## 執行資訊（ae task 產生）

- Issue：#169
- 分支：`feat/trace-plain-page`（從 develop 開出）
- 工作目錄：`.worktrees\trace-plain-page`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
