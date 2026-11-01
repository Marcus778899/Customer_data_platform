TEST_WRONG c517fbe

驗收測試 lock 檢查：跟 lock（commit 6f5ed6b）時一樣，沒被改過

觸發：worker disputes（`.agent/reports/trace-customer-name.json`）；不是 qa FAIL 觸發。

## 依據

| 來源 | 條文 |
|---|---|
| `docs/ui/enrollment-trace.md:20` | 進階資訊（`details`）：進度編號（可複製） |
| `docs/ui/enrollment-trace.md:51` | ID：主要區不顯示；進階資訊可複製（D-080） |
| `docs/ui/enrollment-trace.md:29`、`:37` | FAILED 狀態有「複製診斷資訊」（只含進度編號、時間、`request_id`） |
| `docs/ui/enrollment-trace.md:36` | 只有 `masked_paths` 的值不可解除、不可複製 |
| 派工單 驗收條件 1 | 客戶標示照後端遮罩值顯示；主要畫面不出現客戶 ID |

## 逐條判定

| 測試 | 契約條文 | 判定與理由 |
|---|---|---|
| `web/tests/acceptance/trace-customer-name/customer-label.test.tsx::隱私（D-014、D-021、D-080） > 遮罩值照原樣顯示：不會被還原或補全，也沒有複製按鈕`（第 191 行） | enrollment-trace.md:20、:36、:51 | **錯**。`screen.queryByRole('button', { name: /複製/ })` 查整頁，但 UI 規格要求每個狀態的進階資訊都有「複製進度編號」按鈕（進度編號不是遮罩值，可複製）。此測試用預設 `waiting()` 軌跡，進階資訊必然存在，任何符合規格的實作都會讓這行失敗；同一張單的 `web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx::進階資訊 > 含進度編號（可複製）…`（第 42 行 `within(advanced()).getByRole('button', { name: /複製/ })`）要求相反結果，兩者不可能同時通過。規格只禁止「遮罩值」可複製，不禁止頁面上有其他複製按鈕 |
| `web/tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx::進階資訊 > 含進度編號（可複製）、客戶 ID、旅程 ID 與版本…` | enrollment-trace.md:20 | 符合規格，不改 |

## 測試應該怎麼改（第 191 行）

把「沒有複製按鈕」的範圍限縮到客戶標示出現的地方，而不是整頁。例如：

- 麵包屑：`expect(within(screen.getByRole('navigation', { name: '麵包屑' })).queryByRole('button', { name: /複製/ })).toBeNull()`
- 主要區（排除 `<details>`）：在 `main` 中把不在 `details` 內的按鈕篩出來，斷言沒有名稱含「複製」者（此測試是 waiting 狀態，主要區不會有「複製診斷資訊」）；或只斷言副標所在元素內沒有複製按鈕

測試標題與其餘斷言（遮罩值原樣顯示、不出現 `carol` 等還原值）維持不變。

## 其他

- 實作 `web/src/pages/trace/Advanced.tsx:40` 的「複製進度編號」按鈕符合規格，不應移除。
- 契約問題：無。
