APPROVE 0dff117

PR #175（head 0dff1171），對照派工單 `.agent/tasks/trace-plain-list.md`（含兩段追加）、`docs/ui/enrollment-trace.md` 第 21 行「清單頁」、`docs/ui/journey-platform.md`（第 13、60 行）、D-079、D-080、仲裁 `.agent/reports/trace-plain-list.ruling.md`。PR 狀態 MERGEABLE／CLEAN；CI Changes、Security、Check 皆 SUCCESS。

## 檢查項目

| 項目 | 結果 |
|---|---|
| 改動範圍 | 只動負責檔案：三個頁面、`web/tests/unit/plain-list/`、`app.css`（追加授權）；驗收測試的改動在 qa commit 30c471e、3481a9d（依仲裁，範圍已追加）。未碰 `TracePage.tsx`、`web/src/plain/`、`format.ts`、API、契約 |
| 清單頁欄位（enrollment-trace.md:21） | `EnrollmentBrowsePage`、`SearchPage` 表頭＝客戶、旅程、狀態、目前在哪一步、下一步；頁名「旅程進度」；查詢框標籤「客戶 ID 或進度編號」 |
| D-080 不顯示 ID | 移除查詢頁列內 `enrollment_id`、客戶頁「Enrollment ID」欄與 `（v版本）`；ID 只留在連結 `href` |
| D-079 用詞 | 三個頁面畫面文字已無「Enrollment」（剩下的只在型別、函式名稱、import） |
| 狀態與步驟白話 | 狀態用 `enrollmentStatusText`（篩選選項不附代碼）；步驟用 `stepTitle`，StartNode 回 null 時顯示「旅程開始」；不再顯示節點型別 |
| 客戶頁（追加 1） | 拿掉 `UtcContext.Provider` 與勾選；時間改用 `PlainTime` → `formatPlainTime(…, { relative: false })`，UTC 原值只在 `<time dateTime title>`。基本資料區的 `<Time>` 沒有 Provider，預設不顯示 UTC，行為正確 |
| 客戶頁表格（追加 2）／CSS 範圍 | `table.enr` 只有 `CustomerPage.tsx:189` 用到；規則包在 `@media (min-width: 641px)` 裡，跟 `@media (max-width: 640px)` 的 `table.stack` 卡片化互補不衝突；跟 `.trace-page` 段（app.css:217 起）分開並存，選擇器不互相影響。1280px 實際畫面沒在瀏覽器量（D-069：由 uat 實測） |
| 共用 `stepText`、`nextText`、`StatusBadge`、`CustomerLink` 從 `EnrollmentBrowsePage.tsx` 匯出 | 可接受。`web/src/plain/`、`format.ts` 是禁區，worker 沒有別的負責檔案可放；三個函式都沒有副作用，`CustomerPage`、`SearchPage` 只單向 import，沒有循環相依；eslint 沒開 `react-refresh/only-export-components`，lint 不會擋。建議（不擋）：之後有前端單動到 `web/src/plain/` 或 `components/` 時，把這四個搬過去，頁面之間就不會互相 import |
| 安全與設定 | 連結 `profile_id`、`enrollment_id` 都有 `encodeURIComponent`；沒有寫死的設定值；沒有新的 API 呼叫 |
| 測試 | 驗收 `trace-plain-list/plain-list.test.tsx` 涵蓋派工單列的驗收條件；qa 已 PASS。單元 `next-text.test.ts` 測到 `nextText`／`stepText` 的分支；`customer-utc.test.ts` 是讀原始碼字串的檢查，比較弱，但客戶頁行為已經有驗收測試（customer-page.test.tsx 改寫後的 UTC 測試）覆蓋 |

## 非阻擋備註（給 manager）

- `nextText` 依狀態給固定句子（WAITING＝「時間到會自動繼續」）。設計稿寫的「10/21（三）10:00 發送訊息」要等摘要補上 `wake_at` 跟下一節點才做得到，這是契約缺欄位，仲裁的「契約問題」已經列了，不在本單範圍。
- 「客戶」欄目前顯示「查看客戶」連結，原因也是摘要沒有姓名（同上）。
