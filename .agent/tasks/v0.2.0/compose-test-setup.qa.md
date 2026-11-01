PASS 07b8431

# QA compose-test-setup（PR #97，chore）

## 驗收條件對照

1. compose 單行跑全部後端測試：PASS
   - 指令（主目錄單獨一行）：`docker compose --env-file .env -f .worktrees/compose-test-setup/compose.yaml run --rm test`
   - 輸出尾端（原始）：`[INFO] Tests run: 191, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS` / `Total time: 06:54 min`
   - 含 acceptance：DeliveryPipelineAcceptanceTest 12、DeliveryResilienceAcceptanceTest 5（docker pause）、SchemaV5UpgradeAcceptanceTest 3、EnvExampleNoTenantIdAcceptanceTest 1 等，全數 Failures 0 / Errors 0。與 CI 的 make check（後端全跑）一致。
2. 專案名稱獨立、不留容器：PASS（容器部分）
   - 專案名稱 = compose 檔所在目錄（輸出容器名 `compose-test-setup-test-run-…`）；compose.yaml 未設 `name`。
   - `docker compose --env-file .env -f .worktrees/compose-test-setup/compose.yaml ps -a` 輸出：`NAME IMAGE COMMAND SERVICE CREATED STATUS PORTS`（空）。
   - 專案網路 `<worktree>_default` 會留下（worker 已說明，`compose down` 可刪）；volume `cdp-test-m2` 刻意保留作快取。
3. Testcontainers 做法與安全取捨說明：PASS。docs/design/INIT-01.md「驗證方式」與回報 open_questions 有說明；未改任何測試，docker pause 測試通過。
4. make check 行為與 CI：PASS
   - `make check CONTAINER_TESTS=skip`（worktree 內）：印 `Docker not reachable or CONTAINER_TESTS=skip: skipping container tests (acceptance/, *IntegrationTest).` 與 compose 指令提示；後端 `Tests run: 48, Failures: 0, Errors: 0`；web 135 passed；`check passed`，exit 0。
   - `make check`（Docker 可達）：跑含 acceptance 的完整測試，exit 0。
   - `gh pr checks 97`：`Check pass 4m25s`（head 07b843141fe1 = 驗證的 commit）。
5. README 建議指令已列在回報 open_questions：PASS。

## 掛載範圍檢查（compose.yaml）

- `/var/run/docker.sock`：必要（Testcontainers 兄弟容器）。socket 本身無法以 `:ro` 達成有意義的唯讀，等同主機 Docker root 權限；為已知取捨，文件已載明，在本機信任的開發環境可接受。
- `./api:/src/api:ro`：唯讀；容器內以 tar 複製到 `/workspace/api`（排除 target）後建置，不寫回 host。
- `./.env.example:/workspace/.env.example:ro`：唯讀；EnvExampleNoTenantIdAcceptanceTest 需要。
- `m2:/root/.m2`：具名 volume `cdp-test-m2`（Maven 快取），非 host 路徑。
- 無 `ports`（測試 DB 不對外）、無與測試無關的 volume、無寫回 host 的掛載。未寫死 token／密碼；`TESTCONTAINERS_HOST_OVERRIDE` 為固定主機別名，非機敏設定。
- 範圍檢查：相對 merge-base 僅動 ci.yml（註解）、Makefile、api/pom.xml（unit-only profile）、compose.yaml、docker-compose.yml（註解）、INIT-01.md，均在負責檔案內；未碰禁區。

## 未驗證項目（照實列出）

- Testcontainers／Ryuk 啟動的 PostgreSQL、RabbitMQ 容器跑完是否被清除：未驗證。guard 只允許 `docker compose …`，`docker ps` 無法使用，不繞過。compose 的 `ps -a` 只涵蓋 compose 專案內容器，看不到 Ryuk 管理的容器。建議甲方在主機終端 `docker ps -a` 確認。
- 專案網路是否殘留：未用 docker network ls 驗證，依 worker 說明（會殘留，非容器）。

## 邊界／備註（非 FAIL）

- 主目錄現同時有 compose.yaml 與 docker-compose.yml，不帶 -f 的 `docker compose` 會選 compose.yaml；`make infra-up/down` 皆帶 `-f docker-compose.yml`，行為不變（Makefile diff 已確認）。
- 首次執行需下載映像與相依，約 7–10 分鐘。
