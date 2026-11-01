# 派工單 ci-container-share

- **類型**：chore
- **負責**：qa
- **目標**：CI 加速 #3：驗收測試改成一個 JVM 只起一組共用的 PostgreSQL 與 RabbitMQ，每個 Spring context 用自己的 database 與 vhost；減少 Testcontainers 重複啟動（設計量測 162 秒、佔 surefire 48%）
- **背景**：`docs/design/ci-speedup.md` 做法 #3（甲方已核可）。ci-surefire-fork（PR #120）已合併：forkCount=2 實測只省約 35 秒，architect review 判斷瓶頸在每個 context 各起一組容器加上 4 vCPU 搶資源，共用容器才是主要改善；做完後再比較 forkCount 1／2／3
- **設計依據**：`docs/design/ci-speedup.md` 做法 #3、「威脅與對策」（共用後的隔離、暫停容器的類別）、「受影響的既有驗收測試」、「自行決定」表（`SharedContainers.java` 放 `acceptance/support/`）；`.agent/reports/ci-surefire-fork.review.md`
- **相依**：ci-surefire-fork（已合併）
- **負責檔案**：
  - `api/src/test/java/com/cdp/acceptance/support/SharedContainers.java`（新）
  - `api/src/test/java/com/cdp/acceptance/delivery/DeliveryTestSupport.java`
  - `api/src/test/java/com/cdp/acceptance/engine/EngineTestSupport.java`
  - `api/src/test/java/com/cdp/acceptance/relay-loop-rewrite/RelayLoopTestSupport.java`
  - `api/src/test/java/com/cdp/acceptance/security/SecurityTestSupport.java`
- **禁區**：任何測試的斷言、測試方法、測試資料不改；只換容器來源；`DeliveryResilienceAcceptanceTest`、`RelayStuckTimersAcceptanceTest`（會 docker pause 容器）維持專屬容器；`api/src/main/`、`api/pom.xml`、`.github/`、`Makefile`、`compose.yaml` 不改；不可排除或跳過任何測試；不可讀 .env
- **驗收測試**：無（chore；既有測試全部要過）
- **驗收條件**：
  - [ ] 一個 JVM 只起一組共用 PostgreSQL＋RabbitMQ（Testcontainers singleton，不交給 Spring 管生命週期）；每個 Spring context 各自的 database 與 vhost，資料與佇列互不相見；兩個 pause 類別仍用專屬容器
  - [ ] 後端全部測試通過，測試數量與改動前相同：`docker compose --env-file .env -f .worktrees/ci-container-share/compose.yaml run --rm test`，附 `Tests run` 總數與 Testcontainers 啟動次數（改動前 RabbitMQ 33、PostgreSQL 39 次左右）
  - [ ] 開 PR 後 CI 連續 3 次 Check 全綠（gh run rerun），附各次 Check 時間；並在同一 commit 上與改動前的 develop（ci-surefire-fork 合併後）比較
  - [ ] 若出現偶發失敗：記下測試名稱與原因，不改斷言；屬隔離問題就修共用方式
- **驗證指令**：`docker compose --env-file .env -f .worktrees/ci-container-share/compose.yaml run --rm test`；`gh run view <id> --json jobs`
- **回報檔**：`.agent/reports/ci-container-share.json`

## 執行資訊（ae task 產生）

- Issue：#123
- 分支：`chore/ci-container-share`（從 develop 開出）
- 工作目錄：`.worktrees\ci-container-share`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
