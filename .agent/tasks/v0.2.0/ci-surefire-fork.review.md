APPROVE 5595b29

# Review ci-surefire-fork（PR #120）

## 檢查項目
| 項目 | 結果 |
|---|---|
| 改動範圍 | `gh pr diff 120`：只改 `api/pom.xml` +9 行，是頂層 `<build><plugins>` 的 maven-surefire-plugin，設 `forkCount=2`、`reuseForks=true`。沒碰 `api/src/`、`.github/`、`Makefile`、`compose.yaml`（禁區沒動） |
| 照設計做 | 跟 `docs/design/ci-speedup.md` 做法 #2 與「自行決定」的 fork 數 2 一致；版本沿用 spring-boot-starter-parent 的 pluginManagement，沒有另外寫死 |
| 沒有排除或跳過測試 | 沒加 excludes、skip、groups。`unit-only` profile 原本的 excludes 會跟頂層設定合併，行為沒變（`make check` 在 sandbox 只跑單元測試） |
| 對其他路徑的影響 | `make check`（CI=true → `mvnw verify`）與 compose 的 `test`（`mvnw verify`）都吃這個設定；沒有 failsafe，也還沒有 JaCoCo 或 `make coverage`，所以不會有多 fork 寫同一份 exec 檔的問題（之後補 coverage 時要用 append，JaCoCo 預設就是 append） |
| 固定 port 與共用狀態 | 設計已用 grep 確認 `server.port: 0`、沒有固定 port；每個 fork 有自己的 Ryuk 與容器（attempt 3 的 log 裡 pid 2965 和 2966 各自建立 ryuk） |
| 測試數量 | CI 是 `Tests run: 391, Failures: 0, Errors: 0, Skipped: 0`，65 個類別。worker 用 stash 在本機量的基準也是 391。跟同一份程式碼、改動前的 run 比，類別清單只差在 `SessionRevokeAcceptanceTest`（那個 run 還沒有這個類別，見下面），沒有類別不見 |
| 驗收條件 | 全部達成：3 次 Check 全綠（run 41117703989 attempt 1–3），沒有偶發失敗，所以不用跑 `-DforkCount=1` 對照 |
| 安全 | 沒有安全相關的影響 |

問題數：0

## 節省比預估少的原因（實測，不是推測）
| run（Check job） | 程式碼 | fork | 測試數 | surefire 實際耗時 | 各類別耗時加總 | Check |
|---|---|---|---|---|---|---|
| 41112294636（124257403158） | profile-search-api，合併 develop 前 | 1 | 377 | 295 秒 | 283 秒 | 344 秒 |
| 41113398309（124261336208） | user-session-revoke | 1 | 311 | 337 秒 | 326 秒 | 402 秒 |
| 41114471160（124265029611） | 572a045，跟本 PR 只差 pom | 1 | 391 | log 拿不到（GitHub 回 503 egress limit，重試 4 次都失敗） | — | 351 秒 |
| 41117703989 attempt 3（124281897332） | 5595b29 | 2 | 391 | 261 秒 | 474 秒 | 325 秒 |

1. **qa 拿來比的基準不同**：設計量測表的 403–413 秒是 311 個測試、比較慢的 runner 量的。跟同一天、程式碼幾乎一樣的改動前 run 比（344、351 秒），Check 只少約 0–30 秒，surefire 約少 35 秒（295 → 261 秒，而且改動後還多了 14 個測試）。qa 的數字照派工單要求的基準來比，沒有錯，只是高估了節省。
2. **兩個 fork 搶 CPU，單一類別變慢**：同一個類別在 fork=2 的耗時，是 fork=1 的平均 **1.66 倍**（run 124257403158 對 124281897332，用 `join` 比對 64 個共同類別，加總 283 → 470 秒）。起整個 app 和容器的類別變慢最多：`StartupValidationAcceptanceTest` 1.79 倍、`RelayStartupValidationAcceptanceTest` 1.75 倍、`LastAdminAcceptanceTest` 2.26 倍。主要在等待的 `DeliveryPipelineAcceptanceTest` 只慢 1.17 倍。ubuntu-latest 有 4 vCPU，上面同時跑 2 個 JVM（Spring context 啟動）和 2 組 RabbitMQ（Erlang 啟動吃 CPU）＋ PostgreSQL，CPU 已經滿了。所以實際只快了約 2 / 1.66 ≈ 1.2 倍，設計預估是線性的約 1.84 倍（340 → 185 秒）。
3. **收尾不平均**：470 / 2 = 235 秒，實際花 261 秒，多出約 25 秒是最後一個 fork 還在跑最慢的類別（delivery 類別 47–57 秒）。
4. **runner 本身差很多**：fork=1 時，同一批類別在不同 run 的加總差到 15% 以上（326 對 283 秒，前者測試還比較少）。只跑一次的前後比較不可靠。

## fork 數要不要調
- **維持 2，不要改成 3 或 4**：只開 2 個就已經讓單一類別慢了 1.66 倍，再加 fork 只會更搶 CPU，可能沒有效果甚至變慢。也**不需要退回 1**：確實有省一些（surefire 約 −12%），3 次全綠，沒有出現順序相依的問題。
- 真正有效的是做法 #3（`ci-container-share`）：一個 JVM 只起一組容器，CPU 最吃重的容器啟動（162 秒）就會大幅減少，搶 CPU 的情況也跟著消失。#3 合併後，在**同一個 commit** 上用 `-DforkCount=1`、`2`、`3` 各跑一次再決定，到時 3 才可能有用。

## 設計文件要不要記實測值
- 要。`docs/design/ci-speedup.md` 做法 #2 寫「預估節省約 2.5 分鐘」，實測只有約 0.5 分鐘。如果不改，之後的單（#3、#5）會拿錯的數字估算。
- 建議當成後續單的事，不在本單改：併進 `ci-container-share`（它本來就要量前後時間），或由 architect 開一張只改文件的 chore。要記的內容：上表、各類別的變慢倍數 1.66、「前後比較要用同一個 commit、只差 `-DforkCount`」的量法。
- 量測方法的提醒（給 manager 判斷要不要放進注意事項）：CI 的前後比較要在同一個 commit 上只改一個參數，不要拿設計文件的舊數字當基準（測試數會一直增加，runner 也有 ±15% 的差異）；`gh run view --job` 和 jobs logs API 對重跑過的 run 只會回最新一次 attempt 的 log。

## 驗證（本次執行）
- `gh pr diff 120`、`gh pr view 120 --json headRefOid,files` → 只有 `api/pom.xml`，head 是 5595b29
- `gh run view 41117703989 --json jobs,attempt` → attempt 3，Check success，19:57:10–20:02:35
- `gh api repos/Marcus778899/Customer_data_platform/actions/jobs/124281897332/logs`：`T E S T S` 19:57:51 → `Results` 20:02:11；`Tests run: 391, Failures: 0, Errors: 0, Skipped: 0`；65 個類別；RabbitMQ 容器 36 個、PostgreSQL 容器 49 個
- 用同樣方式抓 jobs 124257403158、124261336208 的 log，當 fork=1 的基準（數字見上表）；124265029611 的 log 回 503，拿不到
- 依指示沒有跑需要容器的測試
