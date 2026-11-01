PASS 149d51c

# plain-copy-glossary 第 2 輪驗證（PR #166）

## 3 項必改
1. `web/src/plain/index.ts` WaitDelayNode：delay 可解析（`isoDurationText(delay) !== delay`）才回「等 <中文期間>」，否則 `nodeName ?? base`。單元測試涵蓋：有名無 delay（含 null）-> 「等 3 天」；有名且 delay=P1W -> 「等 3 天」；無名且 P1W -> 「等待」。已處理。
2. `web/src/format.ts` `formatPlainTime(iso, now, opts?: { relative?: boolean })`，預設 true；false 時今天／昨天／明天／同年／跨年都不帶括號。單元測試涵蓋 6 種輸出（今天 10:00、昨天 09:10、今天 18:00、明天 10:00、10/24（六）14:00、2025/12/30（二）10:00）。已處理。
3. 「約 N 天後」改用 `dayDiff`；單元測試：本地 10/21 23:00 -> 10/23 01:00 得「（約 2 天後）」。已處理。
- 順手：未來今天 59.5～60 分鐘改走小時分支（`mins < 60` 判斷）。

## 執行
- `make check`（worktree 內）：API 397 tests 0 failures、BUILD SUCCESS；web `Test Files 22 passed (22)`, `Tests 310 passed (310)`；結尾 `check passed`，exit 0。
- 單獨跑 `web/tests/acceptance/plain-copy-glossary`（plain-glossary、plain-time）與 `web/tests/unit/plain`：`Test Files 3 passed (3)`, `Tests 84 passed (84)`。
- 驗收測試檔自 lock（acdc5b9）後無改動（review 修正 commit 只動 format.ts、plain/index.ts、unit 測試）；worktree 乾淨。
- 既有函式行為未變（relative 預設 true，既有測試與頁面測試全綠）。
- 無寫死設定值；只動負責檔案。

## 備註（非阻擋）
- 單元測試以 describe 內 `process.env.TZ = 'Asia/Taipei'` 設時區，在目前 vitest 下通過；若以後改 worker 共用可能受影響，建議第 2 批留意。
