PASS 4fb7028

驗證（自己執行）：
- `make check`（在 .worktrees/trace-customer-name）：exit 0，輸出結尾 `check passed`（前端 vitest 32 files / 413 tests 全過；後端 unit-only）
- `npm run lint -w web`（eslint + tsc --noEmit）：無錯誤
- `npx vitest run --root web tests/acceptance/trace-customer-name tests/acceptance/trace-plain-rewrite tests/acceptance/journey-customer-ui`：10 files / 126 tests passed
- `npm test -w web`：32 files / 413 tests passed

驗收條件對照：
1. 客戶標示順序（軌跡姓名 -> 客戶資料未遮罩姓名 -> 第一個識別碼遮罩值 -> 「未具名客戶」）：model.ts `customerLabel` 實作與順序一致；麵包屑中段與副標用同一 `customer.text`，中段連到 `/customers/<id>`；TracePage 主要畫面無客戶 ID 輸出；getProfile 失敗時 useFetch 無資料，退回第 4 步（測試涵蓋）。
2. 驗收測試全綠、`make check` 通過：是。

仲裁核對：與 lock（6f5ed6b）相比，驗收目錄只差 customer-label.test.tsx 第 191 行附近的複製按鈕檢查範圍（改為麵包屑與 details 以外），與仲裁 TEST_WRONG 指示一致；其餘測試未動。範圍限縮仍會抓到麵包屑／主要區出現的複製按鈕。

邊界：姓名被遮罩（masked_paths 含 attributes.name）時不採用，改取識別碼；空白字串姓名／識別碼略過；軌跡已有姓名時不多打客戶 API。無寫死的設定值（port、網址、token）。

問題數量：0
