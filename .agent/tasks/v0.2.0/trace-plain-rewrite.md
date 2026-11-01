# 派工單 trace-plain-rewrite

- **類型**：feat
- **負責**：frontend
- **目標**：D-081（方案 A）：軌跡頁白話改版——首屏三問摘要卡、白話步驟時間軸、訊息發送紀錄、技術細節收進「進階資訊」，讓行銷、業務不需教育訓練就看得懂
- **背景**：甲方驗收時指出軌跡頁（例 `/enrollments/<id>`）只有工程師看得懂；設計 plain-ux-ui（PR #162）甲方已核可；術語與時間模組 plain-copy-glossary（PR #166）已合併。取代作廢的 trace-plain-page（範圍誤寫）
- **設計依據**：`docs/ui/enrollment-trace.md`（方案、畫面由上而下 1～5、摘要組句表、步驟標題、狀態與互動、麵包屑、窄螢幕與無障礙、自行決定表）；設計稿 `docs/ui/enrollment-trace/plain.html`（15 個狀態）；`web/src/plain/`、`web/src/format.ts` 的 `formatPlainTime(iso, now, { relative })`；D-016、D-079、D-080、D-081、D-014／D-021（遮罩）
- **相依**：plain-copy-glossary（已合併）；與 trace-plain-list 可平行
- **負責檔案**：
  - `web/src/pages/TracePage.tsx`
  - `web/src/pages/trace/`（新：從軌跡頁拆出的元件）
  - `web/src/styles/app.css`（只動軌跡頁段落）
  - `web/src/graph/GraphSection.tsx`（若規格要求調整軌跡頁上的圖或移入進階資訊）
  - `web/tests/unit/trace/`（新：單元測試）
- **禁區**：不改 API、契約；不改 `web/src/plain/`、`format.ts`（不夠用就停下回報）；列表、搜尋、客戶頁屬 trace-plain-list；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/trace-plain-rewrite/`（依規格與設計稿：摘要卡三問在等待中／走完待送／已送出／發生錯誤／已中止各狀態的句子；時間軸步驟標題與狀態字、目前步驟 `aria-current="step"`；「接下來」取不到定義時降級；訊息紀錄與無資料說明；進階資訊預設收合且含 ID、UTC 原值等；主要畫面不出現 Enrollment、節點型別名、UTC 原字串、ID（D-079、D-080）；遮罩照舊；載入、404、錯誤狀態；麵包屑；重新整理與「更新於」）
  - 受影響的既有驗收測試：`web/tests/acceptance/trace-page/`、`web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`、`web/tests/acceptance/integration/`、`web/tests/acceptance/session-expired/`、`web/tests/acceptance/journey-customer-ui/narrow-a11y.test.tsx`（只改涉及軌跡頁的部分；qa「A」依新規格改寫後一起 lock）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-plain-rewrite.json`

## 執行資訊（ae task 產生）

- Issue：#170
- 分支：`feat/trace-plain-rewrite`（從 develop 開出）
- 工作目錄：`.worktrees\trace-plain-rewrite`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-11-01

### .agent/reports/trace-plain-rewrite.review.md

REQUEST_CHANGES c2a2821

# Review trace-plain-rewrite（PR #172）

## 檢查範圍

| 項目 | 結果 |
|---|---|
| 變更檔案 vs 負責檔案 | 程式檔都在負責範圍（TracePage.tsx、pages/trace/、app.css、GraphSection.tsx、tests/unit/trace/）；acceptance 檔是 qa 的 lock commit（30720c3）與依仲裁修正（7aa644b），不是 worker 改的 |
| 禁區 | 沒動 API、契約、`web/src/plain/`、`format.ts`、`useFetch.ts`、列表／搜尋／客戶頁的 tsx |
| 規格對照（docs/ui/enrollment-trace.md、plain.html） | 畫面 1～5 順序、摘要卡 `role="status"` 三問、摘要組句表各狀態（WAITING、走完＋PENDING、走完＋SENT、FAILED＋複製診斷、ABORTED、ACTIVE／PENDING、WAITING EVENT 降級）、步驟標題與狀態字、`aria-current="step"`、定義取不到時警示條＋重試＋只列已走過的步驟、訊息紀錄無資料說明、進階資訊預設收合（ID、UTC 原值、as_of、判斷值、系統原因文字經 `localizeIsoInText`、觸發事件、遮罩欄位）、404 文案＋錯誤代碼＋回客戶搜尋、麵包屑「客戶／姓名／旅程進度」、h1 換頁取得焦點、圖形軌跡移到時間軸下方預設收合：符合 |
| D-079／D-080 | 主要區不顯示 Enrollment、節點型別名、UTC、ID；只出現在 Advanced.tsx 與只寫進剪貼簿的 `diagnosticText` |
| 遮罩（D-014／D-021） | 沿用 `KeyValues` 的 masked 處理；副標姓名照後端遮罩值顯示並附「部分資料已遮罩」 |
| 寫死設定 | 無 port、網址、token |
| 安全 | 無 `dangerouslySetInnerHTML`；複製診斷資訊不含個資（單元測試有斷言）；唯讀，無非 GET 請求 |
| 測試 | 驗收測試 lock 版本＋仲裁修正全綠（qa PASS）；`web/tests/unit/trace/model.test.ts` 測 upcoming、steps、降級、診斷不含個資；PR CI（Changes、Check、Security）全 pass |

## 必改（REQUEST_CHANGES）

派工單限定 app.css「只動軌跡頁段落」，新段落裡有幾條是全站選擇器，會改到其他頁（含平行單 trace-plain-list 負責的頁面）：

1. `web/src/styles/app.css:211` — `h2.sec { ... margin: var(--space-5) 0 var(--space-3); }` 蓋過第 134 行全站 `.sec { margin: 0 0 var(--space-3) }`，CustomerPage（基本資料、參加過的旅程）、CustomerSearchPage、EnrollmentBrowsePage 的 `h2.sec` 都多出上方間距 — 改成只作用在軌跡頁（見下方做法）。
2. `web/src/styles/app.css:224` — `.hint { margin: var(--space-1) 0 0; }` 是全站 class；PasswordPage、CustomerSearchPage 的 `.hint` 在 `.field`（flex column）裡是 flex item，margin 會生效、表單間距改變 — 限定在軌跡頁。
3. `web/src/styles/app.css:225`、`:255` — `.btn svg { margin-right }`、`@media (prefers-reduced-motion: reduce) { .btn { transition: none; } }` 改全站按鈕 — 限定在軌跡頁；全站 reduced-motion 若要做，另開單由負責 ui 的單處理。
4. `web/src/styles/app.css:221-240` — `.q`、`.a`、`.step`、`.dot`、`.slab` 等是很泛用的單字 class，目前沒有其他頁使用，但同屬全站命名空間，之後任何頁用到就會被套上 — 一起收進同一個範圍。

做法（擇一，worker 自行決定）：`TracePage.tsx` 的 DetailBody 外包一層 `<div className="trace-page">`（或在既有容器加 class），新段落所有選擇器加 `.trace-page` 前綴；或改用 `trace-` 前綴的 class 名。只改 TracePage.tsx、pages/trace/、app.css 新段落即可，不碰其他頁。改完跑 `make check`。

刪除的舊規則（`.summary`、`.summary.ok`、`ol.trace*`、`.reason`）：grep `web/src` 已無其他使用者，屬舊軌跡頁專用，刪除可接受。

## worker open_questions 判定

| 問題 | 判定 |
|---|---|
| (1) GraphSection 文字版清單疊軌跡時附「（狀態：已完成 N 個、目前在這裡 N 個、接下來 N 個）」 | 接受。只在有 `overlay` 時出現（JourneyPage 不疊軌跡、不受影響）；補的是讀屏用的文字，符合「狀態一律文字＋顏色＋圖示」；位置在預設收合的 details 內，不違反 D-079。不需改 |
| (2) `fetchEnrollment` 同 id 進行中請求去重（`TracePage.tsx:20-27`，模組層 `Map`） | 本單接受，不必改；列 backlog（minor）。理由：`useFetch.ts` 不在本單負責檔案，且有 7 個頁面共用，改語意要另開單並重跑各頁驗收；去重只合併「同 id 還在途中」的請求，完成即刪，不會回舊資料；驗收測試在 StrictMode 下要求重新整理後正好 2 次請求，現寫法通過。backlog 建議：在 `useFetch`（或 `api/client` 的 GET 層）統一處理 StrictMode 重複請求，之後把 TracePage 的局部去重拿掉 |
| (3) advanced-states 偶發逾時 | qa 單獨重跑 3 次與完整 make check 皆通過，不擋本單；若再出現，建議 backlog 調整該測試逾時或等待寫法 |

## 其他（不擋合併，建議 backlog）

- `web/src/pages/trace/model.ts:318` `diagnosticText` 沒有 `request_id`；規格「狀態與互動」寫「只含進度編號、時間、`request_id`」。成功回應的 request id 目前 `api/client.ts` 沒有對外提供（不在本單負責檔案），需另開單讓 client 回傳成功回應的 `X-Request-Id` 再補上。
- 合併順序提醒：本單與 trace-plain-list 都在 app.css 檔尾追加段落，後合併的那張 sync 時會有文字衝突（兩段都保留即可）。

問題數量：4（必改）

## 追加 2026-11-01

### .agent/reports/trace-plain-rewrite.review.md

REQUEST_CHANGES e7fdfe4

# Review trace-plain-rewrite（PR #172）第 2 輪

## 上一輪 4 個必改（c2a2821..e7fdfe4，只動 TracePage.tsx、app.css）

| # | 項目 | 結果 |
|---|---|---|
| 1 | `h2.sec` 蓋過全站 `.sec` | 已改 `.trace-page h2.sec`：通過 |
| 2 | 全站 `.hint` | 已改 `.trace-page .hint`：通過 |
| 3 | `.btn svg`、reduced-motion `.btn` | 已改 `.trace-page .btn svg`、`@media (prefers-reduced-motion) { .trace-page .btn }`：通過 |
| 4 | `.q`／`.a`／`.step`／`.dot`／`.slab` 等泛用 class | 新段落（app.css 209 行到檔尾）每條選擇器都有 `.trace-page` 前綴，含兩個 @media：通過 |

- `TracePage.tsx:151` DetailBody 外層由 fragment 改成 `<div className="trace-page">`；loading、404、ErrorBox 在 DetailBody 之外，只用全站既有 class，不受影響。圖形區 `details.flow-wrap`、`Advanced` 都在這層 div 裡，樣式照常套用
- 沒有新的範圍外改動；整體 diff 其他部分沿用第 1 輪結論（負責檔案、禁區、規格、D-079／D-080、遮罩、安全都符合，見上一輪結果檔）

## 必改

1. `web/src/pages/TracePage.tsx:48-54`：h1 焦點的時序競爭讓 CI Check 變紅。
   - 問題：PR CI（run 41310901487，head e7fdfe4）Check 失敗，失敗的測試是 `web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx::進階資訊 > <details> 預設收合，summary 可鍵盤操作（Enter 展開）`（`expect(d.open).toBe(true)` 得到 false）。本機重現：單獨跑這個檔 5/5 次通過，跑完整前端套件 3 次中失敗 1 次。所以是偶發，而且不是第 2 輪的修改造成的（第 1 輪就記錄過同一個檔的偶發問題）。但 CI 會對之後的每一張單都跑這個測試，偶發紅燈會擋住所有 PR 合併。
   - 原因（從程式推斷）：h1 是在 passive `useEffect` 裡 `focus()`，fetch 回來後的 render 不在 act 裡，effect 由 scheduler 延後執行。測試在 `await heading()`（h1 一出現就 resolve）之後馬上 `summary.focus()` 再按 Enter；如果 effect 剛好排在這之後才跑，焦點會被搶回 h1，Enter 打在 h1 上，`<details>` 就不會展開。同一個檔第 176 行要用 `waitFor` 等 h1 取得焦點，也證明焦點是非同步取得的。
   - 改法：把這段焦點邏輯改成 `useLayoutEffect`（條件與 `focusedFor` 不變）。這樣在 h1 掛上 DOM 的同一次 commit 裡就會取得焦點，早於測試的 MutationObserver 回呼，競爭就不存在了。使用者看到的行為不變（換頁時 h1 取得焦點）。改完跑完整前端套件至少 10 次（`cd web && npx vitest run`），全部通過才交件，再跑 `make check`。如果改成 layout effect 後仍會偶發失敗，就是測試本身的問題，請在回報的 `disputes` 寫明，交仲裁處理，不要自己改驗收測試。

## 其他（沿用第 1 輪，不擋合併）

- `diagnosticText` 缺 `request_id`、`fetchEnrollment` 去重（#173）：backlog
- 本單與 trace-plain-list 都在 app.css 檔尾追加段落，後合併的那張 sync 時會有文字衝突，兩段都保留即可

## 驗證（本輪實際執行）

- `git diff c2a2821..e7fdfe4`：2 個檔，47+/47-，內容如上表
- `gh pr checks 172`：Changes pass、Security pass、Check fail（vitest 1 failed | 378 passed）
- `npx vitest run tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx`（在 web/）×5：每次 Tests 21 passed (21)
- `npx vitest run`（在 web/）×3：兩次 Tests 379 passed (379)；一次 1 failed | 378 passed，失敗的就是上面那個測試

問題數量：1（必改）
