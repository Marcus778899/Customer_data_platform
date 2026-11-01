# 設計：CI 加速（ci-speedup-design）
- 契約：無（不改 API）。依據：`.github/workflows/ci.yml`（`check`、`security` 兩個 job）、`Makefile`（`ci-setup`、`check` = `lint` + `test`）、`api/pom.xml`（surefire 預設單一 fork）、`api/src/test/resources/application-test.yml`、驗收測試支援類 `DeliveryTestSupport`、`EngineTestSupport`、`RelayLoopTestSupport`、`SecurityTestSupport`（各自用 `@Bean @ServiceConnection` 建容器）；量測：job log 用 `gh api repos/{owner}/{repo}/actions/jobs/<id>/logs` 取；各步驟時間用 log 時間戳記與 Maven、Testcontainers、Vitest 的輸出算（指令見回報）

## 量測結果（秒；Check job 的 `make check` 拆解）
| run／job | 事件 | job 總計 | ci-setup | spotless | eslint+tsc | Maven 啟動+testCompile | surefire | vitest |
|---|---|---|---|---|---|---|---|---|
| 41088821210／124176967558 | PR user-session-revoke | 403 | 15 | 3.5 | 8.9 | 6.9 | 339 | 19.8 |
| 41091268596／124185448861 | PR #110 | 409 | 16 | 3.2 | 9.6 | 6.0 | 346 | 20.1 |
| 40068852878／120797730587 | PR profile-search-api | 413 | 13 | 3.6 | 8.8 | 7.4 | 354 | 19.2 |
| 41054164223 第 1／2 次（124057360904／124060408623） | PR user-session-revoke，失敗後重跑 | 381／354 | 10／10 | 3.0／3.0 | 8.2／6.8 | 5.3／4.5 | 340／322 | 測試失敗，未跑 |

| 項目 | 數字（run 41088821210；其他 run 差異 ±5%） |
|---|---|
| surefire 內容 | 55 個類別、311 個測試：單元 20 類 5.2 秒；驗收 35 類約 334 秒 |
| Testcontainers 啟動 | RabbitMQ 33 次共 101.8 秒（每次約 3.1 秒）＋ PostgreSQL 39 次共 60.1 秒 = **162 秒，佔 surefire 48%**；Spring context 23 個（容器跟著 context 走，每個 context 各起一組）；第一個 context 16.9 秒（JVM 暖機、Ryuk、拉映像約 5 秒） |
| 各驗收類別（秒） | delivery 79（2 類，等待佔多數）、userauth 57、relayloop 50、ingest 44、security 35、query 29、schema 12、其他 ≤ 6 |
| 最慢的類別 | `DeliveryPipelineAcceptanceTest` 40、`DeliveryResilienceAcceptanceTest` 39、`DispatchPropertiesStartupAcceptanceTest` 34、`RelayStartupValidationAcceptanceTest` 28、`LoginRateLimitAcceptanceTest` 26、`StartupValidationAcceptanceTest` 17（啟動驗證類每個測試起一次整個 app 與容器） |
| 相依下載 | 不是瓶頸：5 個 job 的 setup-java（maven）與 setup-node（npm）快取都命中，Maven「Downloaded from」0 筆，`npm ci` 4 秒 |
| 排隊 | 最近 30 個 run、37 個 job：等 runner 中位數 3 秒、最多 71 秒；repo 是 public，標準 runner 不計費，目前沒有搶 runner 的實際延遲 |
| 觸發來源（10-18 起 200 個 run） | develop 上只改 `docs/`、`.agent/`、`README.md`、`BOARD.md` 的 push **95 次（48%）**、合計 456 分鐘；PR 66 次；PR 合併的 push 35 次；其他 push 4 次 |
| 13 分鐘那次（41054164223） | 不是排隊或快取未命中。第 1 次 12:16:58–12:23:24（Check 失敗：`LoginSessionAcceptanceTest.role_change_takes_effect_on_next_request`），12:24:12 手動重跑第 2 次到 12:30:10，同一個測試又失敗。`gh run list` 的時間從建立算到最後一次更新，所以是兩次約 6.5 分鐘相加。 |
## 做法（加速方案）
| # | 方案 | 預估節省 | 改的檔 | 風險／驗證範圍 | 受保護路徑／流程檔 |
|---|---|---|---|---|---|
| 1 | develop 的 push 只改文件時跳過 `check` job：`check` 前加一步，用 `git diff --name-only ${{ github.event.before }} ${{ github.sha }}` 判斷，全部符合白名單（`docs/**`、`.agent/**`、根目錄 `README.md`、`BOARD.md`）才跳過；`before` 全 0（新分支、強推）照跑；`security` job 一律照跑 | PR 等待時間不變；runner 時間少約 48% 的 run（兩週約 450 分鐘）；develop 不再因文件提交出現 6 分鐘的黃燈 | `.github/workflows/ci.yml` | 驗證範圍：文件提交不跑測試。`api/`、`web/`、`Makefile` 都不讀這些路徑（grep `docs/`、`spec/` 無結果），結果不受影響；白名單只列文件目錄，改錯會漏跑測試，由 review 與「PR 一律照跑」兜底 | `.github/*` 受保護（architect review）；不是 D-067 的流程檔 |
| 2 | surefire 平行：`api/pom.xml` 設 `forkCount=2`、`reuseForks=true`（以類別為單位分配到 2 個 JVM） | surefire 約 340 → 185 秒，**省約 2.5 分鐘** | `api/pom.xml` | 不減少測試；每個 fork 自己的 Ryuk 與容器；`server.port: 0`、沒有固定 port（grep 確認）；runner 4 vCPU，RabbitMQ 啟動吃 CPU，先 2 再量，不直接開到 4 | `pom.xml` 受保護 |
| 3 | 共用容器：一個 JVM 只起一組 PostgreSQL 與 RabbitMQ（Testcontainers singleton，不交給 Spring 管生命週期，Ryuk 在 JVM 結束時清），每個 Spring context 用 `DynamicPropertyRegistrar` 建自己的 database 與 vhost；會 `docker pause` 容器的 `DeliveryResilienceAcceptanceTest`、`RelayStuckTimersAcceptanceTest` 照舊用專屬容器 | 容器啟動 162 → 約 15 秒，**省約 2.5 分鐘**；與 #2 合併後 surefire 約 100 秒，Check job 約 2.5–3 分鐘 | 新增 `api/src/test/java/com/cdp/acceptance/support/SharedContainers.java`；改 4 個 `*TestSupport.java` | 不減少測試、不改斷言；隔離單位從「每個 context 一組容器」變成「每個 context 一個 database＋vhost」，資料與佇列仍互不相見；cached context 的輪詢迴圈各連自己的 vhost，不會搶別人的訊息 | 改到驗收測試目錄（qa 擁有、已 lock） |
| 4 | PR 的 run 加 `concurrency`（同一 PR 新 push 取消舊 run，只對 `pull_request`；develop 的 push 不取消） | PR 等待不變；`ae pr sync` 連續 push 時少跑被取代的 run | `.github/workflows/ci.yml` | `gh pr checks` 只看最新 commit，取消的是舊 commit 的 run | `.github/*` 受保護 |
| 5 | 前端拆成平行 job（eslint、tsc、vitest），後端 job 只跑 spotless 與 `mvnw verify`；`make check` 本機仍是兩者 | 約 30 秒（前端移出關鍵路徑） | `.github/workflows/ci.yml`、`Makefile`（加 `check-api`、`check-web`，`check` 依賴兩者） | 不減少檢查；等 #2、#3 做完再評估，和之後補 `make coverage`（每端一份報告）一起做較划算 | `.github/*`、`Makefile` 受保護 |
| — | 不採用：Maven／npm 快取（已命中）；Testcontainers `withReuse`（要改 runner 的 `~/.testcontainers.properties`，容器跨 run 殘留、狀態共用，#3 已涵蓋）；Vitest `isolate: false`（只省約 4 秒，卻拿掉測試檔之間的隔離）；縮短 delivery 類的等待（改的是驗收測試的時間語意，省的不多）；跳過 PR 合併後的 push CI（那是合併結果唯一一次完整驗證）。 | — | — | — | — |
## 威脅與對策（含風險）
| 資產 | 威脅 | 對策 |
|---|---|---|
| CI 判定（`ae pr merge` 的閘門） | #1 白名單寫太寬，改程式的 push 被當成文件而沒跑測試 | 白名單只列文件目錄、不用 `**/*.md`；PR 一律跑全套，合併前已驗過 |
| 密鑰掃描 | 文件提交直接進 develop，跳過掃描就會把密鑰推上 public repo | #1 只跳過 `check`，`security` job 不跳過 |
| 驗收結果可信度 | #3 共用容器後測試互相污染，造成假 PASS 或偶發失敗 | 每個 context 各自的 database 與 vhost；暫停容器的類別用專屬容器；合併前在 CI 連跑 3 次全綠 |
| 測試順序相依 | #2、#3 讓既有的隱性相依浮現（偶發失敗） | 先只開 2 個 fork；失敗就回報 qa，用 `-DforkCount=1` 比對 |
## 受影響的既有驗收測試
| 測試檔 | 跟新做法哪裡矛盾 | 交給哪張單改寫 |
|---|---|---|
| `api/src/test/java/com/cdp/acceptance/{delivery/DeliveryTestSupport,engine/EngineTestSupport,relay-loop-rewrite/RelayLoopTestSupport,security/SecurityTestSupport}.java` | 不矛盾；只改容器來源，不改斷言 | `ci-container-share` |
## 檔案分工與派工順序（每張單附前後的 Check job 時間：`gh run view <id> --json jobs`）
| 單 | 負責檔案 | 順序 |
|---|---|---|
| `ci-docs-skip` | `.github/workflows/ci.yml`（#1、#4） | 與 `ci-surefire-fork` 平行（檔案不重疊） |
| `ci-surefire-fork` | `api/pom.xml`（#2） | 同上 |
| `ci-container-share`（qa） | `SharedContainers.java`、4 個 `*TestSupport.java`（#3） | `ci-surefire-fork` 合併後（兩者都改測試執行方式，分開量才知道各省多少） |
| `ci-web-split` | `ci.yml`、`Makefile`（#5） | 前三張的數字出來、補 `make coverage` 時再開 |
## 自行決定（可逆、在範圍內；甲方核可設計時一起看，要改再說）
| 項目 | 決定 |
|---|---|
| fork 數 | 2，量過再調 |
| `SharedContainers.java` 放上層 `acceptance/support/`、由 qa 那張單建立 | 它的用途就是驗收測試的共用 fixture；architect review |
## 待甲方決定／需要調查
- 待決定：#1 文件提交不跑測試（④ 驗證範圍變小；理由與兜底見做法 #1、威脅表；不做的話其他方案照常）
- 需調查：GitHub Actions job 被 `if:` 跳過時，`gh pr checks` 的結束碼是否為 0（影響 #1 能否延伸到只改文件的 PR；目前只用在 develop 的 push，不受影響）
