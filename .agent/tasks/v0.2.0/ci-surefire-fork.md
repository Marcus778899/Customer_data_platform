# 派工單 ci-surefire-fork

- **類型**：chore
- **負責**：backend
- **目標**：CI 加速 #2：surefire 以 2 個 JVM 平行跑測試（`forkCount=2`、`reuseForks=true`），讓 CI 的 Check job 縮短約 2.5 分鐘
- **背景**：`docs/design/ci-speedup.md`（PR #114 已合併，甲方已核可）量到 Check job 約 6.5–7 分鐘，其中 surefire 約 340 秒；做法 #2 預估 340 → 185 秒
- **設計依據**：`docs/design/ci-speedup.md` 做法 #2、「威脅與對策」的「測試順序相依」列、「自行決定」表（fork 數 2，量過再調）
- **相依**：無
- **負責檔案**：
  - `api/pom.xml`（只限 surefire／failsafe 的平行設定）
- **禁區**：`api/src/`（含測試）一律不改；`.github/`、`Makefile`、`compose.yaml` 不改；不可為了平行而排除、跳過或標記忽略任何測試；不可讀 .env
- **驗收測試**：無（chore；既有測試全部要過）
- **驗收條件**：
  - [ ] `api/pom.xml` surefire 設定 `forkCount=2`、`reuseForks=true`；本機與 CI 測試數量和改動前相同（附前後 `Tests run` 總數）
  - [ ] 後端全部測試通過：`docker compose --env-file .env -f .worktrees/ci-surefire-fork/compose.yaml run --rm test`，附輸出摘要
  - [ ] PR 的 CI 連續 3 次 Check 全綠（可用 `gh run rerun <id>` 重跑），附 3 個 run 連結與各自 Check job 時間；與改動前（約 403–413 秒，見設計文件量測表）比較
  - [ ] 若平行後浮現偶發失敗：用 `-DforkCount=1` 對照，回報是哪個測試、是否為既有的順序相依；不自行改測試
- **驗證指令**：`docker compose --env-file .env -f .worktrees/ci-surefire-fork/compose.yaml run --rm test`；`gh run view <id> --json jobs`
- **回報檔**：`.agent/reports/ci-surefire-fork.json`

## 執行資訊（ae task 產生）

- Issue：#119
- 分支：`chore/ci-surefire-fork`（從 develop 開出）
- 工作目錄：`.worktrees\ci-surefire-fork`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
