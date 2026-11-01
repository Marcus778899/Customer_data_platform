PASS 6ba5ef4

# QA trace-plain-rewrite（PR #172，第 3 輪，h1 焦點改 useLayoutEffect）

## 驗證
- `make check`（worktree，Docker 可達，後端 mvnw verify 含容器測試）：BUILD SUCCESS；web `Test Files 27 passed (27) / Tests 379 passed (379)`；`check passed`，exit 0
- 前端套件連跑 12 次（`cd web && npx vitest run`）：11 次 `379 passed (379)`，1 次（run7）`1 failed | 378 passed`。加上 make check 內那次，共 13 次中 1 次失敗
- 失敗測試名（run7）：`tests/acceptance/trace-plain-rewrite/advanced-states.test.tsx > 從客戶頁進入仍可用（整合） > 深層連結 401 → 登入 → 回原軌跡頁並顯示白話畫面`（1555ms）
  錯誤：`TestingLibraryElementError: Unable to find role="heading" and name /旅程的進度/`（advanced-states.test.tsx:235 `await heading()`），當時 DOM 仍是登入頁（form 在、無白話畫面）
- 上一輪 review 指出的目標測試「Enter 展開進階資訊」：13 次全數通過，該競爭已消除
- PR #172 最新 CI（run 41328633138，`gh pr checks 172`）：Changes pass、Check pass（3m28s）、Security pass

## 偶發失敗判斷：測試等待寫法問題，不是實作時序
- 失敗點在「按登入 → POST login → 重抓 /auth/me → 導回 → 抓 enrollment → 渲染」整串鏈，卡在登入頁，h1 根本還沒出現；與 TracePage 的 h1 焦點（useLayoutEffect）無關
- 登入／認證／路由程式本單未改（`git diff origin/develop...HEAD -- web/src` 只有 GraphSection、TracePage、trace/*、app.css）
- 測試用真實 userEvent 逐字輸入再 `findByRole`，預設等待 1000ms；27 個 worker 並行時整檔 6 秒，鏈路超過 1 秒即失敗（該測試自己耗時 1555ms）。屬驗收測試的等待時間過短
- 建議：此測試的 `await heading()` 加較長 timeout（例 `{ timeout: 5000 }`）。驗收測試不在 backend／本單可改範圍，請 manager 走仲裁／A 流程或開 backlog；不擋本單合併

## 問題數量
0（FAIL 項目無；殘留 1 個非實作的測試偶發，已記錄）
