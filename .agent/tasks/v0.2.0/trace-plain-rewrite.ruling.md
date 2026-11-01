TEST_WRONG 71bb283

驗收測試 lock 檢查：跟 lock（commit 30720c3）時一樣，沒被改過

## 判定

| 測試 | 契約條文 | 判定與理由 |
|---|---|---|
| `web/tests/acceptance/trace-page/trace-page.test.tsx` 的「詳情 > COMPLETED 但有 PENDING 發送：摘要寫排隊中與聯絡技術人員，發送紀錄結果為「等待發送」」 | `docs/ui/enrollment-trace.md` 畫面 2（摘要卡 `role="status"`）、摘要組句表「走完＋有 PENDING 發送」 | 測試錯（等待寫法）。第 279 行在 `start()` 後馬上 `await screen.findAllByRole('status')`，只要畫面上有任何 `role=status` 就會立刻 resolve。`App` 第一次 render 時 `auth.status === 'checking'`，會輸出 `<main role="status" aria-busy="true">`（`web/src/App.tsx:137`，既有行為，不在本單負責檔案）。所以第一次就只拿到這個元素，`.find(...)` 得到 `undefined`，第 280 行拋 TypeError。不管軌跡頁怎麼實作都一定失敗。斷言內容本身符合契約：「旅程已走完」「1 則訊息在排隊」「聯絡技術人員」「等待發送」都對得上組句表與設計稿 `docs/ui/enrollment-trace/plain.html` 的對應狀態；同一情境在 `web/tests/acceptance/trace-plain-rewrite/summary.test.tsx`（「走完＋有待送訊息…」）已通過 |

## 測試應該怎麼改（qa）

只改第 279 行的等待寫法，斷言不動。下面兩種擇一：

- 先等軌跡頁載入完成：`await h1()`（同檔第 79 行已有 helper），再 `const card = screen.getAllByRole('status').find((s) => s.textContent?.includes('這位客戶現在在哪'))!`
- 或等到摘要卡出現：`const card = await waitFor(() => { const c = screen.getAllByRole('status').find((s) => s.textContent?.includes('這位客戶現在在哪')); expect(c).toBeTruthy(); return c! })`

同目錄其他測試、`trace-plain-rewrite/fixtures.tsx` 的摘要卡查找已用「先等再 getAll」的寫法，照那個寫法即可。

## 驗證

- `npx vitest run --root web tests/acceptance/trace-page/trace-page.test.tsx -t "PENDING"`（worktree）：1 failed，`TypeError: Cannot read properties of undefined (reading 'textContent')`，位置 `trace-page.test.tsx:280:17`
- `npx vitest run --root web tests/acceptance/trace-plain-rewrite/summary.test.tsx -t "走完"`：3 passed（同一情境用正確等待寫法時，實作符合契約）

## 回報檔格式（worker 下一輪要依 schema 修正）

`.agent/reports/trace-plain-rewrite.json` 不符合 `.claude/skills/agent-engineering/contracts/report.schema.json`。下一輪請照 schema 修正：

- 缺少必填欄位 `files_changed`（陣列）、`open_questions`（陣列）
- `verification` 必須是物件 `{command, exit_code, tail}`，現在寫成字串
- `disputes[].conflict` 要改成 `reason`（item 的 `additionalProperties: false`）
- 頂層不允許 `summary`（`additionalProperties: false`），內容請移到 `open_questions`，或直接刪掉

## 契約問題

無。
