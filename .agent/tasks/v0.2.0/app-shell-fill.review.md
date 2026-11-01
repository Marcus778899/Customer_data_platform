APPROVE 007f77c

## 第 2 輪檢查範圍
- 修正 commit `007f77c`（只改 `web/src/styles/app.css`，+2 −1）與整體 `gh pr diff 167`（`Shell.tsx`、`app.css`、qa 的 `web/tests/acceptance/app-shell-fill/`、`narrow-layout` 一行）
- 對照派工單 `.agent/tasks/app-shell-fill.md`（含追加的第 1 輪 REQUEST_CHANGES d5e28bc）、`docs/ui/journey-platform.md`「外框撐滿視窗」、`docs/ui/enrollment-trace.md`「麵包屑」
- CI：Changes／Check／Security 全部 pass（head 007f77c）；qa `PASS 007f77c`

## 第 1 輪問題 1 的修正
| 項目 | 結果 |
|---|---|
| `.app` 補 `padding: var(--space-5)` | app.css:4。符合要求 |
| 640px 以下 `.app { padding: var(--space-4) }` | app.css:66（第一個 640px 區塊）。符合，與原本 body 的 24px／16px 留白一致 |
| 會不會讓外框又出現留白 | `.app` 只用在 `web/src/App.tsx:137`（checking）、`App.tsx:144`（error）；`Shell` 與 `LoginPage`（`.login-wrap`）都不在 `.app` 裡，所以不影響外框撐滿與登入頁置中。沒有引入新問題 |
| 既有驗收測試 | `narrow-layout.test.tsx:171` 只檢查 `.app` 的 `max-width`，沒變；`app-shell-fill` 測試只檢查 `body` padding，也不受影響 |
| 寫死值 | 只用 token（`--space-5`、`--space-4`）。符合 |

## 整體 diff 結論
- 第 1 輪對照規格的那張表（body 無 padding、`.shell` 無框線與圓角加 100dvh、`.side` sticky、`.top` sticky、`.page` `min-width: 0`、登入頁置中、麵包屑結構與對齊、分隔符）在 007f77c 都維持不變，仍然符合
- 負責檔案：worker 的 commit 只改 `Shell.tsx`、`app.css`；`tokens.css`、`format.ts`、各頁、`LoginPage.tsx`、驗收測試都沒碰。符合
- 測試：qa 的驗收測試涵蓋了驗收條件中 jsdom 能驗的部分；像素依 D-069 由階段 uat 實測

## 非阻擋備註（第 1 輪已提，不擋合併）
- `.login-wrap`、`.shell`、`.side` 沒有 `100vh` 備援（舊瀏覽器不支援 dvh）；worker 回報說不加，可以接受
- `.page` 的 `overflow-wrap: anywhere` 可能讓表格裡的長 ID 在字中間斷行，留給 uat 確認
