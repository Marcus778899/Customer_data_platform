PASS 634421d

# customer-table-fit 驗證（fast 通道）

## 範圍檢查
`git diff --name-only develop...HEAD`：只有
- web/src/pages/CustomerPage.tsx
- web/src/pages/JourneyPage.tsx（只改連結文字）
- web/src/styles/app.css
- web/tests/unit/plain-list/customer-fit.test.ts

app.css 變動：只在客戶頁 `table.enr` 段與新增 `.cols.two.cust`、`.cust ...` 選擇器；`.cust` class 只加在 CustomerPage 的 `cols two`，未動 `.trace-page` 段、未改共用 `.cols`、`table.plain` 規則。無 API／契約變動。

## 驗收條件
- R-F1：`td.nw` 允許在空格正常換行、th 不換行、badge nowrap、欄位 min-width、客戶頁左欄 300px（>=1101px）讓出寬度、`.scroll` 保留橫向捲動作最後保險。CSS 與 DOM 對應（CustomerPage 第 203/212/215 行 td 有 `nw`）。像素版面依 D-069 由 uat 重測，qa 未做瀏覽器實測。
- R-F2：JourneyPage 連結改為「旅程進度」。
- R-F4：CustomerPage 畫面文字移除「（D-016）」；剩餘 D-編號全在程式註解。`.cust h3 + .muted`、`.cust .scroll > table.plain` 補間距。
- 單元測試：新增 customer-fit.test.ts（4 項）。注意為原始碼字串比對，屬弱測試，版面正確性仰賴 uat。

## make check（在 worktree 自行執行）
後端 BUILD SUCCESS；web vitest：
```
 Test Files  31 passed (31)
      Tests  400 passed (400)
check passed
```
## 備註
無需要 FAIL 的問題。建議 uat 第 3 輪重測 1280/1440/500/375 的客戶頁表格。
