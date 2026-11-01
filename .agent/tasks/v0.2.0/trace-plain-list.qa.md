PASS 0dff117

## 驗證（獨立執行）
- `cd .worktrees/trace-plain-list && make check`：exit 0。後端 Tests run: 397, Failures 0, Errors 0, BUILD SUCCESS；前端 vitest `Test Files 30 passed (30)`、`Tests 396 passed (396)`；結尾 `check passed`（含 lint、tsc、build）。
- 驗收測試 `web/tests/acceptance/trace-plain-list/` 與依仲裁改寫的 enrollment-browse、customer-page、trace-graph、trace-page、integration 全在上述全綠範圍內；unit/plain-list 2 檔亦綠。

## 對照
- 禁區：`git diff develop...HEAD --stat -- web/src/plain web/src/format.ts web/src/pages/TracePage.tsx` 無變更；API／契約未動。
- app.css：diff 只新增 8 行，一段 `@media (min-width: 641px)` 且選擇器全是 `table.enr`（badge nowrap、第 1／3 欄 min-width 8em、padding-inline）；`.trace-page` 段（第 217 行起）完整保留並存，無衝突標記。`table.enr` 對應 CustomerPage.tsx:189 `<table className="stack enr">`，窄螢幕仍走 `stack` 卡片化（媒體查詢 >640px 才生效）。
- 甲方追加：CustomerPage.tsx 已無「同時顯示 UTC 原值」勾選與表內 UTC 文字（grep 僅剩註解，UTC 只在 time 的 dateTime／title）；customer-page 測試已依仲裁改寫並通過。
- 用詞：三個頁面原始碼中無畫面用 Enrollment 字樣（僅為程式識別字）；欄位、白話狀態、查詢框標籤由 plain-list 驗收測試 13/13 覆蓋。
- 仲裁後測試改寫範圍僅限 ruling 列出的檔案與斷言。

## 備註（不構成 FAIL）
- 1280px 實際版面未瀏覽器實測（D-069 由 uat 負責）；CSS 以規則審視與測試通過為準。
- 契約缺欄位（客戶姓名、WAITING 下一步時間）worker 以「查看客戶」連結與固定文案處理，已列於仲裁「契約問題」，另開單。
