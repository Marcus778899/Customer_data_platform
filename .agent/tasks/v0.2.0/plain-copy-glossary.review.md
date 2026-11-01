APPROVE 149d51c

## 檢查範圍（第 2 輪）

- `gh pr diff 166` 全部（`web/src/format.ts`、`web/src/plain/index.ts`、驗收測試兩檔、`web/tests/unit/plain/plain.test.ts`），修正 commit `git show 149d51c`（只動 `format.ts`、`plain/index.ts`、單元測試）
- 對照：派工單（含兩次追加）、上一輪 review 的 3 個必改項、`docs/ui/enrollment-trace.md`「步驟標題」、`plain.html#time`
- 驗收測試未被改：`git diff 640ccc8 149d51c -- web/tests/acceptance` 無輸出
- 範圍：只動負責檔案；沒有寫死設定值；無安全問題（只做字串組合與替換）
- CI：Changes／Check／Security 皆 pass（run 41306376495）；qa PASS
- 本機：`npx vitest run --root web tests/unit/plain tests/acceptance/plain-copy-glossary` → `Test Files 3 passed (3)`、`Tests 84 passed (84)`

## 上一輪必改項

| # | 項目 | 結果 |
|---|---|---|
| 1 | `stepTitle` 等待步驟丟掉 `node_name`、delay 無法解析顯示 ISO 原字串 | 已修：`plain/index.ts:76-77` delay 可解析才「等 <中文>」，否則 `nodeName ?? base`；單元測試涵蓋有名無 delay、delay 為 null、delay 無法解析（有名／無名） |
| 2 | `formatPlainTime` 沒有不帶相對時間的寫法 | 已修：`format.ts:98` 加 `opts: { relative?: boolean } = {}`，預設 `true`，既有呼叫與驗收測試不受影響；單元測試涵蓋今天（過去／未來）、昨天、明天、同年、跨年 |
| 3 | 「約 N 天後」與日曆日判斷矛盾 | 已修：`format.ts:124` 改用 `dayDiff`；`dayDiff > 0` 與舊的 `!past` 在 `|dayDiff| >= 2` 時等價，過去日期行為不變；單元測試涵蓋 10/21 23:00 → 10/23 01:00 =「約 2 天後」 |

上一輪觀察「約 60 分鐘後」也一併修了（`format.ts:115-117`：分鐘數四捨五入到 60 時改走小時分支，顯示「約 1 小時後」）。

## 新引入問題

無。

## 不擋合併的觀察

- `web/tests/unit/plain/plain.test.ts:26` 在 `describe` 內設 `process.env.TZ`（qa 也提到）：靠 vitest 每檔獨立 worker 與 collection 先於執行才成立，會同時影響同檔其他 describe；驗收測試是放在模組頂層（`plain-time.test.ts:20`）。建議之後移到檔案頂層或 vitest 設定的 `env.TZ`，本單不必改
- `plain/index.ts:77` `nodeName ?? base`：`node_name` 為空字串時回空字串（其他分支用 truthiness 會退回型別名）；契約是 `string | null`，實務不會出現，可在第 2 批或下次改到時統一成 `nodeName || base`
- 上一輪其餘觀察（v0.3.0 節點有名稱時的組句、狀態色仍在舊 map、步驟狀態字由第 2 批定義）維持，不擋合併

問題數量：0（必改）＋2（觀察）
