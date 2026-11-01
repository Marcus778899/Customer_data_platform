# 派工單 plain-copy-glossary

- **類型**：feat
- **負責**：frontend
- **目標**：D-079～D-081：建立白話術語與時間格式模組，供軌跡頁改版（trace-plain-page）與列表改詞（trace-plain-list）共用
- **背景**：產品給行銷、業務使用，畫面不再出現 Enrollment、節點型別名、UTC 原字串、ISO 期間等工程用語；設計 plain-ux-ui（PR #162，甲方已核可）定了術語對照表與時間規則
- **設計依據**：`docs/ui/enrollment-trace.md`「摘要組句」「步驟標題」「自行決定」表；設計稿 `docs/ui/enrollment-trace/plain.html#glossary`（術語對照表）、`#time`（時間規則，例「10/21（三）10:00（約 3 天後）」、今天／昨天／明天）；`docs/ui/journey-platform.md` 派工順序第 1 批；D-016、D-079、D-080
- **相依**：無（與 app-shell-fill 可平行）
- **負責檔案**：
  - `web/src/format.ts`（新增時間函式；既有函式行為不變，舊頁面照常）
  - `web/src/plain/`（新：術語對照——狀態、發送結果、略過原因、出口、管道名（MOCK＝「測試管道（不會真的送出）」）、節點型別白話名與步驟標題組句、ISO 期間轉中文；`decision_reason` 內 ISO UTC 轉本地時間）
  - 上述檔案的單元測試
- **禁區**：不改任何頁面（頁面套用在第 2 批）；不改 API、契約；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/plain-copy-glossary/`（依 `plain.html#glossary` 與 `#time` 逐列：每個狀態／結果／原因／管道／節點型別的白話文字；步驟標題組句含 `node_name` 為 null 的退回；ISO 期間（PT15S、PT2H、P3D 等）轉中文；相對時間與今天／昨天／明天邊界（以固定的「現在」與時區測）；未知值有合理退回、不丟例外）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] 既有頁面行為與既有測試不受影響
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/plain-copy-glossary.json`

## 執行資訊（ae task 產生）

- Issue：#165
- 分支：`feat/plain-copy-glossary`（從 develop 開出）
- 工作目錄：`.worktrees\plain-copy-glossary`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-11-01

- **負責檔案**：補 `web/tests/unit/plain/`（專案單元測試慣例目錄，原派工單「上述檔案的單元測試」即指此處）
- 明天的時間在 24 小時內附「（約 N 小時後）」：與 `plain.html#time` 規則一致即可，保留

## 追加 2026-11-01

### .agent/reports/plain-copy-glossary.review.md

REQUEST_CHANGES 640ccc8

## 檢查範圍

- `gh pr diff 166`：`web/src/format.ts`（+52，新增 `formatDuration`、`formatPlainTime`）、`web/src/plain/index.ts`（新）、驗收測試兩檔、`web/tests/unit/plain/plain.test.ts`
- 對照：派工單（含 2026-11-01 追加）、`docs/ui/enrollment-trace.md`「摘要組句」「步驟標題」「自行決定」、`plain.html#glossary`／`#time`、契約 `docs/contracts/journey-engine.md` §4（`EnrollmentSummary` 只有 `current_node_type`／`current_node_name`，沒有 duration；`trace[].evaluated.duration`）、`docs/ui/journey-platform.md` 派工順序
- 範圍：只動負責檔案；既有函式未改；沒有寫死設定值；沒有 innerHTML 類輸出，`localizeIsoInText` 只做字串替換，無安全問題
- 術語對照：九種狀態、五種發送結果、六種略過原因、MOCK、四種出口、十種節點型別文字與 `#glossary` 一致；未知值退回原值、不丟例外
- 時區：`formatPlainTime` 用瀏覽器本地時區、以本地日曆日判斷今天／昨天／明天（`dayStart` 差值取 round，DST 不會差一天），符合 D-016
- CI：Changes／Check／Security 皆 pass；qa PASS
- 實測邊界（暫時測試檔跑完已刪，worktree 乾淨）：
  `formatPlainTime('2026-10-22T17:00:00Z', 本地 10/21 23:00)` → `10/23（五）01:00（約 1 天後）`；
  `formatPlainTime('2026-10-21T06:59:40Z', 本地 14:00)` → `今天 14:59（約 60 分鐘後）`；
  `stepTitle('WaitDelayNode', '等 3 天')` → `等待`；`stepTitle('WaitDelayNode', '等 3 天', 'P1W')` → `等 P1W`

## 介面是否撐得住第 2 批

重點：`trace-plain-page`、`trace-plain-list` 的負責檔案不含 `format.ts` 與 `web/src/plain/`（`journey-platform.md:59-60`），介面不足時第 2 批只能在頁面裡繞路（正規式剝字串）或越界改檔，所以缺口要在這張補。

## 要修改

1. `web/src/plain/index.ts:75-76` — `WaitDelayNode` 沒有 `delay` 時一律回「等待」，丟掉非 null 的 `node_name`；違反設計「`node_name` 為 null 才退回型別白話名」（`enrollment-trace.md:33`）。`trace-plain-list` 的「目前在哪一步」只有 `current_node_type`／`current_node_name`（契約 §4 `EnrollmentSummary`，無 duration），等待中的進度全部只會顯示「等待」，而契約範例的名稱「等 3 天」可用。另外 `delay` 無法解析時會顯示「等 P1W」這類 ISO 原字串。— 改成：`delay` 可解析（`isoDurationText(delay) !== delay`）→「等 <中文期間>」；否則 `nodeName ?? base`。補單元測試（有名無 delay、有名且 delay 無法解析）。
2. `web/src/format.ts:98-123`（`formatPlainTime`）— 一律附相對時間，沒有只要「日期＋時間」的寫法；但摘要組句與設計稿要的是不帶括號的句子：「10/21（三）10:00 之後自動繼續」「10/21（三）10:00 結束」「已在 10/21（三）10:00 送出」「今天 10:00 更新」（`plain.html` 等待中、已走完、已送出各狀態）。第 2 批不能改 `format.ts`，只能剝「（…）」。— 改成：加第三個選填參數，例 `formatPlainTime(iso, now, opts?: { relative?: boolean })`，預設 `true`（既有驗收測試不受影響），`false` 時只回「今天 10:00」「昨天 09:10」「10/21（三）10:00」「2025/12/30（二）10:00」；補單元測試。
3. `web/src/format.ts:122` — 「約 N 天後」用 `Math.round(abs / DAY_MS)`，日曆差 2 天但不足 1.5×24 小時時會寫「約 1 天後」（實測 `10/23（五）01:00（約 1 天後）`，現在是 10/21 23:00），和同一函式「明天」的日曆日判斷互相矛盾。— 改成用日曆日差 `dayDiff`（已在 101 行算好）；補一個跨日邊界的單元測試。

## 不擋合併的觀察（可順手改或留給第 2 批）

- `format.ts:111` 未來今天內，59.5～60 分鐘會顯示「約 60 分鐘後」；可在 `abs < 3_600_000` 的分鐘數四捨五入到 60 時改走小時分支
- `plain/index.ts:79` v0.3.0 的 SplitTest／Merge／Goal／Exit 有名稱時會組成「A/B 分組：名稱」，`#glossary` 這幾列沒有名稱；v0.2.0 不會出現，v0.3.0 再對齊
- 狀態色仍在 `format.ts` 的 `ENROLLMENT_STATUS`／`DELIVERY_STATUS`（舊文字＋tone），新文字在 `plain/`；第 2 批取文字用 `plain/`、取 tone 用舊 map，舊 label 之後可清掉
- 「步驟狀態字」（已完成、目前在這裡、接下來、發生問題、已中止）、欄位名（預計繼續時間、第 N 版）不在本單列出的範圍，第 2 批在頁面內定義即可

問題數量：3（必改）＋4（觀察）
