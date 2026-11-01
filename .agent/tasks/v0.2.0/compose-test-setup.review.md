APPROVE 07b8431

# Review compose-test-setup（PR #97）

## 檢查範圍

- `gh pr diff 97`：6 檔（ci.yml 註解、Makefile `test`／`help`、api/pom.xml `unit-only` profile、compose.yaml 新、docker-compose.yml 註解、INIT-01.md「驗證方式」），都在負責檔案內；沒碰禁區（`api/src/main`、`api/src/test/java`、`application-test.yml`、`web/`）
- 沒有寫死 secret、port、網址；`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` 是固定主機別名，不是部署設定
- 測試 DB 不對外開 port（compose 沒有 `ports:`；Testcontainers 的隨機 port 與在主機直接跑時相同）

## 重點項目

| 項目 | 判定 | 理由 |
|---|---|---|
| 掛 docker.sock 是否必要 | 必要，可接受 | 禁區不准改測試；現有測試用 Testcontainers 自起 PG／RabbitMQ，`DeliveryResilienceAcceptanceTest` 還要 `docker pause`，測試行程本身就得能呼叫 Docker API。更窄的做法評估：(a) compose 內放 db／mq、測試改連固定主機：要改測試（禁區）且 pause 測試仍需 Docker API；(b) docker-socket-proxy 限縮 API：Testcontainers 需要 create／start／exec／pause／pull／network，等於全開，且 proxy 擋不了 create 時的 privileged／bind mount，效益低又多一個映像。權限與「主機上直接跑 Testcontainers」相同，沒有比 CI／開發者主機更大；與測試無關的掛載沒有加（只有 `api/:ro`、`.env.example:ro`、具名 volume） |
| `-Punit-only` 排除是否可靠 | 可靠 | profile 只覆寫 surefire `excludes`，保留預設的 `**/*$*`；排除 `com/cdp/acceptance/**` 與 `**/*IntegrationTest.java` |
| `make check` 自動略過會不會讓 CI 默默略過 | 不會 | 條件第一項 `[ -n "$CI" ]`：GitHub Actions 一律設 `CI=true`，CI 一律全跑，Docker 壞了是紅燈不是略過。略過分支只在本機發生，且會印出提示與 compose 指令 |
| 新慣例是否寫進文件 | 有 | `docs/design/INIT-01.md`「新測試慣例」列與 `api/pom.xml` profile 註解 |
| 現有測試是否符合新慣例 | 符合 | grep `Testcontainers／@Container／*Container／@SpringBootTest／@ServiceConnection`：命中檔案全部在 `com/cdp/acceptance/`；acceptance 以外 17 個測試（architecture、common、delivery、engine、ingest、journey、persistence、query、security）都沒有容器或 Spring context |
| 違反新慣例時的失敗模式 | 大聲失敗 | 放錯位置的容器測試不會被排除，sandbox 的 `make check` 直接報錯，CI 仍全跑；不會造成靜默漏測 |
| `make infra-up`／`infra-down`／`demo-seed` 行為 | 不變 | `COMPOSE` 仍帶 `-f docker-compose.yml`；`infra-up`、`infra-down`、`demo-seed` 配方未改；docker-compose.yml 只改註解 |
| 專案隔離與殘留 | 符合 | compose.yaml 不設 `name:`，專案名 = worktree 目錄；`run --rm` 不留 test 容器（qa `ps -a` 為空）；原始碼複製進容器建置，不寫回主機，不留 root 擁有的檔案 |

## 非阻擋建議（給 manager 排後續，不影響本次合併）

1. 新慣例目前只在 INIT-01.md 與 pom 註解；worker 寫單元測試時主要讀 `.claude/skills/backend-standards/references/java.md`，建議另開單把「需要容器的測試只放 `acceptance/` 或命名 `*IntegrationTest`」加進去（非本單負責檔案）
2. Maven 快取 volume `cdp-test-m2` 被所有 worktree 共用，平行跑兩個 `run --rm test` 時可能同時寫本機 repo；目前影響小（多半是下載同一相依），若之後遇到損毀再改成每個 worktree 一個 volume
3. Ryuk 是否清乾淨 Testcontainers 容器、專案網路殘留，qa 因 sandbox 無法驗證；建議甲方在主機終端 `docker ps -a`、`docker network ls` 確認一次
4. 主目錄同時有 compose.yaml 與 docker-compose.yml，不帶 `-f` 的 `docker compose` 會選 compose.yaml；README 更新時（manager）一併提醒手動操作要帶 `-f`

## 問題數量

阻擋問題 0；非阻擋建議 4
