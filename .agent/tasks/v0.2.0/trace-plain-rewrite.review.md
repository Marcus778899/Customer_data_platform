APPROVE 6ba5ef4

# Review trace-plain-rewrite（PR #172）第 3 輪

## 上一輪必改（e7fdfe4..6ba5ef4）

| 項目 | 結果 |
|---|---|
| `web/src/pages/TracePage.tsx:48-55` h1 焦點改 `useLayoutEffect` | 通過。只換 hook（import 由 `useEffect` 改 `useLayoutEffect`，`useEffect` 已無其他使用者），條件（`shown && !waitingDef && focusedFor.current !== shown.enrollment_id`）與 deps 不變，附一行註解。行為：h1 在同一次 commit 取得焦點，換頁焦點行為不變；不在 SSR 環境，沒有 useLayoutEffect 警告疑慮 |
| 範圍 | 本輪 1 個檔、3+/2-，在負責檔案內；沒有動驗收測試 |
| 效果 | qa 13 次執行中，目標測試「Enter 展開進階資訊」全部通過；PR CI（head 6ba5ef4）Changes、Check、Security 全 pass |

## 殘留偶發：`web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx::從客戶頁進入仍可用（整合） > 深層連結 401 → 登入 → 回原軌跡頁並顯示白話畫面`

判定：不擋合併。

- 失敗時 DOM 停在登入頁，代表卡在「POST login → 重抓 /auth/me → 導回原網址」這一段，還沒走到 TracePage 的渲染與 h1 焦點。登入、認證、路由的程式本單沒改（`git diff --stat origin/develop...6ba5ef4` 的 `web/src` 只有 GraphSection、TracePage、pages/trace/*、app.css）
- 原因在測試的等待寫法：`fixtures.tsx:162` 的 `heading()` 用 `findByRole` 預設 1000ms，前面又用 userEvent 逐字輸入，整串流程在並行負載下會超過 1 秒（qa 記錄失敗那次耗時 1555ms）。實作沒有違反契約或規格
- 修正屬驗收測試（qa 負責，worker 不可改）：`heading()` 或這一條呼叫加長 timeout（例 `{ timeout: 5000 }`）。#168 已有留言記錄
- 風險提醒給 manager：這是本 PR 新增的測試，合併後每張 PR 的 CI 都會跑，失敗率約 1/13（本機）。上一輪因同類偶發要求修正，是因為原因出在實作（焦點競爭）；這一條原因在測試，不能靠改實作解決，所以不擋本單。建議 #168 優先處理（qa 改測試 timeout），在它修好前遇到 CI 這一條紅燈可重跑

## 整體 diff 結論（沿用第 1、2 輪）

- 負責檔案、禁區、規格（docs/ui/enrollment-trace.md、plain.html）、D-079／D-080、遮罩（D-014／D-021）、安全、寫死設定：都符合，見第 1 輪表格
- app.css 新段落全部收在 `.trace-page` 範圍：第 2 輪已確認
- 驗收測試改動都是 qa 的 lock commit（30720c3）與仲裁修正（7aa644b）

## 其他（不擋合併，backlog）

- `diagnosticText` 缺 `request_id`、`fetchEnrollment` 去重（#173）
- 本單與 trace-plain-list 都在 app.css 檔尾追加段落，後合併的那張 sync 時兩段都保留

## 驗證（本輪實際執行）

- `gh pr view 172 --json headRefOid,commits,statusCheckRollup`：head 6ba5ef4；Changes、Security、Check 皆 SUCCESS
- `git diff e7fdfe4..6ba5ef4`：`web/src/pages/TracePage.tsx` 1 個檔，3+/2-，內容如上
- `git diff --stat origin/develop...6ba5ef4`：18 個檔，`web/src` 只有負責檔案
- `gh issue view 168 --comments`：已有本條偶發的留言

問題數量：0
