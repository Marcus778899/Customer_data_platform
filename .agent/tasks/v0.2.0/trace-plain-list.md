# 派工單 trace-plain-list

- **類型**：feat
- **負責**：frontend
- **目標**：D-079、D-080：進度列表、軌跡查詢頁與客戶頁改用白話用詞——Enrollment 改稱「旅程進度」、ID 不當主要欄位、狀態與步驟用白話
- **背景**：甲方驗收回饋，產品給行銷、業務使用；設計 plain-ux-ui（PR #162）甲方已核可；術語與時間模組 plain-copy-glossary（PR #166）已合併
- **設計依據**：`docs/ui/enrollment-trace.md` 第 21 行「清單頁」（改稱「旅程進度」，欄位＝客戶、旅程、狀態、目前在哪一步、下一步；ID 不當欄位；查詢框標「客戶 ID 或進度編號」）；`docs/ui/journey-platform.md`（客戶頁區塊標題、派工順序第 2 批）；設計稿 `docs/ui/journey-platform/index.html`、`docs/ui/enrollment-trace/plain.html`；`web/src/plain/`、`web/src/format.ts`
- **相依**：plain-copy-glossary（已合併）；與 trace-plain-rewrite 可平行（檔案不重疊）
- **負責檔案**：
  - `web/src/pages/EnrollmentBrowsePage.tsx`、`web/src/pages/SearchPage.tsx`、`web/src/pages/CustomerPage.tsx`（只改「參加過的旅程」區塊的用詞、欄位與時間格式）
  - `web/tests/unit/plain-list/`（新：單元測試）
- **禁區**：不改 API、契約；不改 `TracePage.tsx`、`app.css`、`web/src/plain/`、`format.ts`（不夠用就停下回報）；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/trace-plain-list/`（進度列表與查詢頁：頁名與用詞「旅程進度」、欄位照規格、狀態與目前步驟白話、時間用 `formatPlainTime`、不顯示 ID 欄、查詢框標籤；客戶頁「參加過的旅程」區塊標題與欄位；畫面不出現 Enrollment 字樣）
  - 受影響的既有驗收測試：`web/tests/acceptance/journey-customer-ui/enrollment-browse.test.tsx`、`web/tests/acceptance/journey-customer-ui/customer-page.test.tsx`、`web/tests/acceptance/journey-customer-ui/fixtures.tsx`（qa「A」依新規格改寫後一起 lock）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-plain-list.json`

## 執行資訊（ae task 產生）

- Issue：#171
- 分支：`feat/trace-plain-list`（從 develop 開出）
- 工作目錄：`.worktrees\trace-plain-list`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-11-01

## 甲方意見（2026-11-01）

- 客戶頁勾選「同時顯示 UTC 原值」後版面爆掉：「參加過的旅程」表格的 UTC 長字串把欄位撐爆，「目前／最後節點」被擠成一字一行、出現橫向捲軸（截圖 `C:/Users/Marcus/AppData/Local/Temp/claude/D--workplace-CustomerDataPlatform/924dc974-dfe2-4e67-94b6-e97e6a580b8b/images/5.png`；與 uat v0.2.0 F-2 同一區塊）
- 處理（依已核可設計：UTC 原值不在主要畫面，收進軌跡頁「進階資訊」，仍滿足 D-016）：
  1. 移除客戶頁的「同時顯示 UTC 原值」勾選與表格內的 UTC 文字；UTC 原值只留在 `<time dateTime>`／`title`
  2. 「參加過的旅程」表格在 1280px 不擠壓：各欄不逐字換行、不出現橫向捲軸；窄螢幕照既有 `table.stack` 卡片化
- **負責檔案**：補 `web/src/styles/app.css`（只動客戶頁「參加過的旅程」表格段；軌跡頁段落屬 trace-plain-rewrite，不碰）

## 追加 2026-11-01

### .agent/reports/trace-plain-list.ruling.md

TEST_WRONG 8a0e61a

驗收測試 lock 檢查：跟 lock（commit 30c471e）時一樣，沒被改過

觸發：worker disputes（`.agent/reports/trace-plain-list.json`），非 qa FAIL。
判定依據（「契約」）：`docs/ui/enrollment-trace.md` 第 21 行「清單頁」與「自行決定」表（移除「同時顯示 UTC」勾選）；設計稿 `docs/ui/enrollment-trace/plain.html#after-list`（客戶欄是 `<a>`、狀態選項「全部／進行中／等待中／發生錯誤」不附代碼）；D-079、D-080；派工單「追加 2026-11-01」甲方意見 1；術語模組 `web/src/plain/`（COMPLETED＝「已走完」）；本單 lock 的 `web/tests/acceptance/trace-plain-list/plain-list.test.tsx`。

實跑（worktree `web/`）：`npx vitest run tests/acceptance` → Test Files 4 failed | 19 passed，Tests 21 failed | 349 passed。失敗全部在下列 4 個檔，逐條判定如下。

## 逐條判定

### A. 本單驗收範圍內（lock 版本）

| 測試 | 契約條文 | 判定與理由 |
|---|---|---|
| `web/tests/acceptance/journey-customer-ui/customer-page.test.tsx::「同時顯示 UTC 原值」勾選後時間旁加註 UTC 原值` | 派工單追加 2026-11-01 第 1 點：移除客戶頁的「同時顯示 UTC 原值」勾選，UTC 原值只留在 `<time dateTime>`／`title`；`enrollment-trace.md`「自行決定」表「時間」列 | TEST_WRONG。測試要求勾選存在，與甲方追加直接矛盾。改法：改成「客戶頁沒有『同時顯示 UTC 原值』控制項（`queryByLabelText` 為 null）；畫面文字不含 `2026-05-03T03:20:00Z`；該時間的 `<time>` 元素 `dateTime`（或 `title`）為 UTC 原值」 |

本範圍其餘測試（`trace-plain-list/plain-list.test.tsx` 13 個、`journey-customer-ui/enrollment-browse.test.tsx`、customer-page 其餘）符合契約，實作已通過，不用改。

### B. 不在本單驗收範圍（需追加範圍，由 qa 依新規格改寫後一起 lock）

| 測試 | 契約條文 | 判定與理由 |
|---|---|---|
| `web/tests/acceptance/trace-page/trace-page.test.tsx::查詢列表 > 初始顯示提示；Enter 送出帶 q 查詢並同步網址 ?q=，列表含旅程名、狀態文字、連結` | plain.html#after-list 客戶欄為連結；plain-list.test.tsx 檔頭「客戶欄顯示文字與連結位置由 worker 決定」；`web/src/plain/` COMPLETED＝「已走完」（plain-list.test.tsx 也斷言「已走完」） | TEST_WRONG。①`getAllByRole('link').length toBe(2)` 假設每列只有 1 個連結，新設計客戶欄本身就是連結，屬過度限定；改為逐列斷言有 `href` 以 `/enrollments/<id>` 結尾的連結（同 plain-list.test.tsx「每列有連到該筆旅程進度詳情的連結」寫法）。②`toContain('已完成')` 改為「已走完」 |
| `web/tests/acceptance/trace-page/trace-page.test.tsx::查詢列表 > 網址帶 ?q= 直接查詢；狀態篩選帶 status 參數` | plain.html#after-list 選項不附代碼；plain-list.test.tsx「狀態篩選選項為白話（不附英文代碼）」（兩份測試互相矛盾，以新設計為準） | TEST_WRONG。`getByRole('option', { name: /WAITING/ })` 改為 `{ name: '等待中' }`（或 `selectOptions(sel, 'WAITING')` 用 value），送出仍斷言 `status=WAITING` |
| `web/tests/acceptance/trace-page/trace-page.test.tsx::查詢列表 > 有 next_cursor 才顯示「載入更多」，點擊帶 cursor 並附加列；沒有則不出現` | 同上連結條文；D-080（ID 不當欄位，主要區不顯示） | TEST_WRONG。①link 數 `toBe(2)` 改為資料列數 2（`getAllByRole('row').length` 扣表頭）或斷言有 `href` 結尾 `/enrollments/E_PAGE2` 的連結。②`table.textContent toContain('E_PAGE2')` 與 D-080 矛盾（ID 不可出現在畫面），改為檢查連結 `href` |
| `web/tests/acceptance/trace-page/trace-page.test.tsx::詳情 > 從列表點連結進詳情，網址 /enrollments/:id，焦點移到 h1` | 同上連結條文 | TEST_WRONG。`getAllByRole('link')[0]` 假設第一個連結是詳情；改為選 `href` 以 `/enrollments/${EID}` 結尾的連結（或該列名稱含「棄單挽回」的連結） |
| `web/tests/acceptance/trace-page/trace-page.test.tsx::唯讀與鍵盤 > 列表與詳情沒有任何寫入操作（暫停、重送、編輯、刪除…），且不發出非 GET 請求（auth 除外）` | 同上 | TEST_WRONG。同上，改選詳情連結；其餘斷言不變 |
| `web/tests/acceptance/trace-page/trace-page.test.tsx::唯讀與鍵盤 > 鍵盤：Tab 可到達查詢按鈕與列表連結，Enter 進詳情；展開區是原生 <details>` | 同上 | TEST_WRONG。`link` 改選詳情連結；每列多一個客戶連結，Tab 次數（目前 6）需依新 DOM 加大到足以到達詳情連結，斷言意圖（Tab 可到達、Enter 進詳情）不變 |
| `web/tests/acceptance/integration/integration.test.tsx::不載入 mock，打真實端點 > 每個請求都是相對的 /api/v1/ 路徑並帶 credentials（same-origin 或 include）` | 同上 | TEST_WRONG。第 105 行 `getAllByRole('link')[0]` 改選 `href` 結尾 `/enrollments/${EID}` 的連結；失敗訊息「找不到載入更早紀錄」是點到客戶連結進了客戶頁的連帶結果。注意：若照現況點到客戶頁，`/api/v1/profiles/...` 請求也須是相對路徑，改寫後不影響此斷言 |
| `web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`（13 個，`openTrace` 共用 helper：圖形軌跡：進行中 8 個、終態 1 個、定義取得失敗與資料對不上 4 個） | 本單 lock 的 plain-list.test.tsx 把客戶頁欄名由「旅程（版本）」（UI-03 舊稿 `journey-platform/index.html`）改為「旅程」，連結名稱只要求 `/棄單挽回/`；`enrollment-trace.md` 第 5 點版本收在詳情頁「進階資訊」 | TEST_WRONG。`openTrace` 第 30 行 `findByRole('link', { name: '棄單挽回（v3）' })` 把「連結文字含版本」寫死，新規格未要求（欄名已拿掉「版本」），屬過度限定。改為 `{ name: /棄單挽回/ }`（fixture 客戶頁只有一筆，名稱唯一）。13 個測試本體不用改 |

## 結論

測試檔與契約矛盾 → TEST_WRONG。實作在上列範圍內未發現與契約不符之處；改寫後應重驗。

需追加進本單「驗收測試」範圍、交 qa 改寫的路徑：
- `web/tests/acceptance/trace-page/trace-page.test.tsx`（上表 6 個測試）
- `web/tests/acceptance/integration/integration.test.tsx`（1 個測試）
- `web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`（`openTrace` helper）
已在範圍內、需改寫：`web/tests/acceptance/journey-customer-ui/customer-page.test.tsx`（UTC 勾選測試）

## 契約問題（非決策四類，不開 D-編號）

- 清單頁「客戶」欄：設計稿顯示遮罩姓名（`陳＊＊`），但 `EnrollmentSummary` 只有 `profile_id`、沒有姓名；worker 暫以「查看客戶」連結呈現。屬 `enrollment-trace.md`「需調查」同類（摘要缺顯示欄位），若要顯示姓名需改契約，另開單。
- 清單頁 WAITING 的「下一步」：設計稿寫「10/21（三）10:00 發送訊息」，摘要沒有 `wake_at` 與下一節點；同上，屬契約缺欄位。
- UI-03 舊稿 `docs/ui/journey-platform/index.html` 客戶頁仍是「旅程（版本）」「目前／最後節點」「進入時間」，與白話改版不一致，建議 designer 同步更新以免日後再起爭議。

- **驗收測試**：`web/tests/acceptance/trace-plain-list/plain-list.test.tsx`、`web/tests/acceptance/journey-customer-ui/enrollment-browse.test.tsx`、`web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`、`web/tests/acceptance/trace-page/trace-page.test.tsx`、`web/tests/acceptance/integration/integration.test.tsx`（仲裁 TEST_WRONG 後納入範圍，由 qa「A」依 .agent/reports/trace-plain-list.ruling.md 改寫）
