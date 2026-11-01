# 派工單 demo-seed-integration

- **類型**：chore
- **負責**：frontend
- **目標**：DESIGN-03 批 5 INT-02：前後端整合與 demo-seed 端到端——前端各頁接上已合併的真實後端（`auth.md`、`profiles.md`、`journeys.md` §2、`/enrollments` 瀏覽），demo-seed 建出足以操作所有第一版功能的示範資料
- **背景**：FE-02／FE-03（app-shell-login、journey-customer-ui）開發時對契約 mock；後端 user-auth-api、user-session-revoke、profile-search-api 已合併。第一版驗收含「三種角色依矩陣放行／拒絕」「四種方式搜到客戶並開圖形軌跡」。D-069：瀏覽器實測在階段收尾的 uat，本單以自動測試與實跑證據為準
- **設計依據**：`docs/design/DESIGN-03.md` 做法 #3（demo-seed 以管理金鑰經 `POST /users` 建三種角色帳號）、#4、#6、派工順序批 5；`docs/contracts/auth.md`、`profiles.md`、`journeys.md` §2；D-031、D-048、D-062
- **相依**：無
- **負責檔案**：
  - `tools/demo-seed/`
  - `web/src/`（只限與真實後端對不上的修正）
  - `web/tests/`（只限 `unit/` 與本單新增的整合測試；`web/tests/acceptance/` 不改）
- **禁區**：`api/`（後端與契約對不上時停下來回報，由 architect 修契約或開後端單，不自行改）；`docs/contracts/`；`.github/`、`Makefile`；不可讀 .env；帳密、金鑰只用 `.env` 變數名
- **驗收測試**：無（chore；既有前後端驗收全綠，由 qa 驗證）
- **驗收條件**：
  - [ ] `make demo-seed`（後端以 `make api-run` 跑起來）建出：VIEWER／EDITOR／ADMIN 三種角色帳號（密碼來自 `.env` 變數）、若干客戶（含可用四種識別方式搜到的）、直線旅程與跑過的 Enrollment；可重複執行不壞
  - [ ] 逐一列出前端每個 API 呼叫與契約章節的對照，標明已用真實後端實跑確認（附請求與回應摘要，個資遮罩）；有對不上的列出並說明怎麼處理
  - [ ] 實跑：三種角色登入、權限矩陣（`auth.md` §2）抽查、四種方式搜客戶→客戶頁→圖形軌跡、旅程列表→唯讀旅程頁（標頭、版本切換、節點圖）、Enrollment 瀏覽；以 API 層或自動化測試留證據
  - [ ] `make check` 全綠；README「快速開始」若要改，把要改的指令列在回報（README 由 manager 改）
- **驗證指令**：`make demo-seed`；`make check`；`docker compose --env-file .env -f .worktrees/demo-seed-integration/compose.yaml run --rm test`
- **回報檔**：`.agent/reports/demo-seed-integration.json`

## 執行資訊（ae task 產生）

- Issue：#127
- 分支：`chore/demo-seed-integration`（從 develop 開出）
- 工作目錄：`.worktrees\demo-seed-integration`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-10-31

## 補齊交件缺口（manager）

- **負責檔案**：
  - `.env.example`（只新增 `CDP_DEMO_PASSWORD`，註明可選；不設時 demo-seed 隨機產生並印出一次）
  - `Makefile`（只新增 `demo-verify` target 與 `.PHONY`，執行 `tools/demo-seed/verify.py`；recipe 只用 ASCII；不進 `make check`）
- 做完後在 worktree 實跑 `make demo-seed`（設與不設 `CDP_DEMO_PASSWORD` 各一次，設的那次用指令列臨時值，不寫進任何檔）與 `make demo-verify`，附輸出摘要；`make check` 仍綠
- README 要補的兩句（`CDP_DEMO_PASSWORD` 可選、`make demo-seed` 會印出可搜尋的識別碼、`make demo-verify` 用法）列在回報，README 由 manager 改
- 結束前停掉你自己啟動的 `make api-run` 背景程序（若仍在跑），容器可以留著

## 追加 2026-10-31

### .agent/reports/demo-seed-integration.review.md

REQUEST_CHANGES 6da0cc1

# Review demo-seed-integration（PR #133）

## 檢查範圍

| 項目 | 結果 |
|---|---|
| 變更檔案 vs 負責檔案 | `.env.example`、`Makefile`、`tools/demo-seed/seed.py`、`tools/demo-seed/verify.py`；都在派工單（含 2026-10-31 追加）範圍內；`api/`、`docs/contracts/`、`web/` 未動 |
| Makefile | 只多了 `demo-verify` target 與 `.PHONY`；recipe 只用 ASCII；沒進 `make check` |
| 寫死設定 | 沒有；port 與金鑰從環境變數讀，`CDP_DEMO_PASSWORD` 只寫變數名稱 |
| verify.py 與 auth.md §1／§1.1／§2／§3 | 角色矩陣的期望值都符合 §2（events、PUT profiles、POST journeys 用 session 時連 ADMIN 也是 403；users 是 403/403/200；ADMIN 打不存在的路徑回 404）；CSRF 先於角色檢查，符合 §1；限速：每次先成功登入（計數歸零）才打一次錯密碼，重複跑也不會鎖帳號（§3 計數規則） |
| verify.py 與 profiles.md §1.1／§3、journey-engine §4、journeys.md §2 | 四種搜尋都檢查了 `matched_by`；軌跡、deliveries、版本節點涵蓋、列表欄位、404 code 都對得上契約 |
| seed.py | PUT profiles 用攝取金鑰（§2 矩陣允許）；PHONE 格式 `+886912xxxxxx` 符合 §1.1 regex；重跑時 PATCH 重設密碼，沿用同一個 `password` |
| 驗收條件 | 角色、矩陣抽查、四種搜尋、軌跡、旅程頁、Enrollment 瀏覽都由 verify.py 在 API 層留下證據（D-069）；qa 已註明對照表很精簡、矩陣每列只抽一條路徑，可以接受 |
| CI | Check、Changes 通過；Security 沒過，原因是 osv-scanner 掃到 `minimatch` 10.2.6（GHSA-g3hv-75wg-m249），出在 package-lock.json，不是這個 PR 造成的，由 npm-minimatch-upgrade（D-078）處理 |

## 要修改（1 項，阻擋合併）

1. `.env.example:75` — `CDP_DEMO_PASSWORD=` 沒有註解掉，值又是空的。`check_env.missing_settings()` 會把它當成「甲方必填、還沒填」，合併後 `ae check-env` 會失敗，`ae task new` 也會被擋（task.py:249），甲方得先填一個值才能開下一張單。這跟派工單寫的「註明可選」相反，也違反「選填設定寫成註解 `# KEY=`」的慣例。— 改法：改成 `# CDP_DEMO_PASSWORD=`（上一行說明保留）。改成註解後，Makefile 的 `ENV_VARS`（只抓沒註解的行）就不會 export 它，寫在 `.env` 的值也傳不到 python。所以 Makefile 第 14 行附近要加一行 `export CDP_DEMO_PASSWORD`，不要寫成 recipe 前綴，否則 make 印出指令時會把密碼一起印出來。這行超出追加單限定的 Makefile 範圍，manager 要先把它加進負責檔案。

## 小問題（不阻擋，可以一起修，或排進 backlog）

| 位置 | 問題 | 建議 |
|---|---|---|
| `tools/demo-seed/seed.py:12`、`:212` | 有設 `CDP_DEMO_PASSWORD` 時，docstring 跟輸出的「passwords are new on every run」都不對；而且會把 `.env` 裡設好的密碼印到 console，沒必要 | 有設時印 `(CDP_DEMO_PASSWORD)` 代替明文，標題也依情況改寫 |
| `tools/demo-seed/seed.py:8`、`:134` | docstring 說識別碼「never conflict」，但 PHONE 取 `(tag+n) % 10^6`，不同批次還是可能撞號；撞到時 PUT 會因識別碼唯一而失敗 | 改 docstring，或把 PHONE 也改成跟 tag 一一對應（例如用 7 位以上） |
| `tools/demo-seed/verify.py:122`、`:141` | 前提沒成立時（最新 6 筆裡沒有 carol，例如共用的後端上有其他單同時在建 enrollment；或短等待還沒 COMPLETED），`next()` 會丟 StopIteration 直接中止，不是記成 FAIL 繼續跑 | 改成 `next(..., None)`，找不到就記 FAIL 並提示「先跑 make demo-seed、等短等待完成」 |

## 前端圖形推導（manager 加問：完成軌跡第一筆是 EventSourceNode，沒有 StartNode 條目）

不用改。契約 journey-engine §4.5「走過的連線」列已經寫明：「`StartNode` 不寫軌跡：`entry_node` → `trace[0].node_id` 視為已走過」。後端的行為符合契約。前端 `web/src/graph/model.ts:82-87`（`deriveOverlay`）已經照這條處理：把 `entry_node` 加進 visited，把連到 `trace[0]` 的那條邊標成 taken。驗收測試 `web/tests/acceptance/journey-customer-ui/trace-graph.test.tsx`（「開始」顯示「已經過」）也測到了，fixture 同樣沒有 StartNode 的軌跡。逐步紀錄依 §4.5 只列 trace，沒有「開始」這一列也符合契約。

## manager 補充（範圍與處理方式）

- **負責檔案**：
  - `Makefile`（再加一行 `export CDP_DEMO_PASSWORD`，不要寫成 recipe 前綴）
- 阻擋項照 review 改：`.env.example` 改成 `# CDP_DEMO_PASSWORD=`（註解，可選），Makefile 加 `export CDP_DEMO_PASSWORD`；改完確認 `ae check-env` 的判定邏輯不會把它當必填（讀 `.claude/skills/agent-engineering` 的 check-env 說明即可，不要改流程檔，也不要讀 `.env`）
- 三個小問題一起修：seed.py 有設密碼時不印明文、文案與行為一致；PHONE 不再跨批次撞號；verify.py 前提不成立時記 FAIL 繼續跑，不中止
- 前端圖形推導（StartNode）不用改
- 實跑：`make demo-seed`（設與不設各一次，設的那次用指令列臨時值）、`make demo-verify`、`make check`；結束前停掉你自己啟動的程序，不要讓 `make api-run` 留在背景等待（交件前一定要結束，否則回報會卡住）
