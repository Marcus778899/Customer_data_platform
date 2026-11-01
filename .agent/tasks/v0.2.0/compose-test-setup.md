# 派工單 compose-test-setup

- **類型**：chore
- **負責**：architect
- **目標**：補上範本預期的 `compose.yaml` 與 `test` 服務，讓需要 DB／MQ 的後端測試（含驗收）能以 `docker compose --env-file .env -f .worktrees/<id>/compose.yaml run --rm test` 在本機跑
- **背景**：本機 Bash 在 sandbox 內，只有單獨一行的 `docker compose …` 能碰 Docker（`backend-standards` 第 6 節、`qa.md`）。專案只有 v0.1.0 的 `docker-compose.yml`（postgres、rabbitmq，供 `make infra-up`），後端整合測試靠 Testcontainers 在 `make check` 內自起容器，sandbox 裡連不到 Docker（`make check` 191 測試 129 errors）；目前只有 CI 跑得過。relay-loop-rewrite 的驗收測試（Testcontainers、會 `docker pause` RabbitMQ）因此本機無法驗證。
- **設計依據**：`.claude/skills/backend-standards/SKILL.md` 第 6 節；`.claude/agents/architect.md`（compose 與 CI 說明）；`docs/design/INIT-01.md`；現有 `docker-compose.yml`、`Makefile`、`.github/workflows/ci.yml`
- **相依**：無
- **負責檔案**：
  - `compose.yaml`（新）、`docker-compose.yml`
  - `Makefile`、`.github/workflows/ci.yml`
  - `api/Dockerfile.test` 或 `docker/`（新，若需要）
  - `api/pom.xml`（只限測試執行相關設定）、`api/src/test/resources/testcontainers.properties`（新，若需要）
  - `docs/design/INIT-01.md`（驗證方式段落）
- **禁區**：`api/src/main/`；`api/src/test/java/`（含 `acceptance/`）；`api/src/test/resources/application-test.yml`（relay-loop-rewrite 負責）；`web/`；不可讀 .env；compose 不加與測試無關的 volume 掛載，測試用 DB 不對外開 port；不改 `make infra-up`／`demo-seed` 的既有行為（要改先回報）
- **驗收測試**：無（chore；由 qa 實跑驗證）
- **驗收條件**：
  - [ ] 在主目錄單獨一行 `docker compose --env-file .env -f .worktrees/compose-test-setup/compose.yaml run --rm test` 能跑完後端全部測試（含 `acceptance/`），結果與 CI 一致；回報附輸出摘要
  - [ ] 每個 worktree 專案名稱各自獨立（平行開發不互撞），跑完不留下容器（附 `docker compose … ps -a` 或等效證據）
  - [ ] 若沿用 Testcontainers：說明在 `test` 服務內如何連到 Docker 與安全取捨；若改連 compose 內的 DB／MQ：說明對既有測試（含會 pause RabbitMQ 的測試）的影響與需要的後續單
  - [ ] `make check` 在 sandbox 內的行為說明清楚（不需 Docker 的部分照跑，需要 Docker 的指向 compose 指令）；CI 仍綠
  - [ ] README「快速開始」要更新的指令列在回報（README 由 manager 改）
- **驗證指令**：`docker compose --env-file .env -f .worktrees/compose-test-setup/compose.yaml run --rm test`；`make check`
- **回報檔**：`reports/compose-test-setup.json`

## 執行資訊（task.py 產生）

- Issue：#96
- 分支：`chore/compose-test-setup`（從 develop 開出）
- 工作目錄：`.worktrees\compose-test-setup`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
