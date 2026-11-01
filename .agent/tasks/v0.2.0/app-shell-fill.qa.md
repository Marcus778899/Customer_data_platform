PASS 007f77c

# app-shell-fill 第 2 輪驗證

- 追加項目（review：`.app` 補 padding）：`git diff d5e28bc HEAD` 只動 app.css 兩處：`.app { ...; padding: var(--space-5); }`，640px 區塊加 `.app { padding: var(--space-4); }`。符合追加要求；`.app` 只被 App.tsx:137、144（載入中、登入狀態錯誤）使用，未影響 Shell／登入頁。
- body 不留白：app.css 第 3 行 `body { margin:0; padding:0 }` 未變，640px 區塊內沒有 body padding；驗收測試 app-shell-fill 與改寫後的 narrow-layout 含於全綠結果。
- 未寫死顏色、port，使用 token（--space-4／--space-5）。
- 其他驗收條件（外框 100dvh、無框線圓角、sticky、`.page` min-width:0、麵包屑、登入頁置中）上一輪已確認，本輪 diff 未碰，驗收測試仍綠。
- `make check`（在 .worktrees/app-shell-fill 實跑）：
  - 後端：`Tests run: 397, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
  - 前端：`Test Files 20 passed (20)`、`Tests 239 passed (239)`
  - 結尾：`check passed`，exit code 0
- 像素版面依 D-069 留給階段 uat。
- 非阻擋（不算 FAIL）：未加 100vh 備援。
