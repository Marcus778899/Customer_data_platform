# 派工單 ci-docs-skip

- **類型**：chore
- **負責**：architect
- **目標**：CI 加速 #1、#4：develop 上只改文件的 push 跳過 `check` job（`security` 照跑，D-075）；PR 的 run 加 `concurrency`，同一 PR 有新 push 取消舊 run
- **背景**：`docs/design/ci-speedup.md`（PR #114，甲方已核可，D-075）量到近 200 次 CI 有 48% 是 develop 上只改文件的 push；`ae pr sync` 連續 push 會跑被取代的 run
- **設計依據**：`docs/design/ci-speedup.md` 做法 #1、#4 與「威脅與對策」（設計文件目前在 `.worktrees/ci-speedup-design/docs/design/ci-speedup.md`，PR #114 合併前以那份為準）；D-075
- **相依**：無
- **負責檔案**：
  - `.github/workflows/ci.yml`
- **禁區**：`security` job 不可跳過或改條件；PR（`pull_request`）一律跑全套；白名單只列 `docs/**`、`.agent/**`、根目錄 `README.md`、`BOARD.md`，不用 `**/*.md`；develop 的 push 不取消；`api/`、`web/`、`Makefile` 不改；不可讀 .env
- **驗收測試**：無（chore；由 qa 依下列條件實測）
- **驗收條件**：
  - [ ] 判斷邏輯：`before` 全 0（新分支、強推）照跑；diff 有任一檔不在白名單就照跑；全部在白名單才跳過 `check`；附在本機用 `git diff --name-only` 對幾個歷史 commit（只改文件／改到 api／混合）模擬判斷結果的輸出
  - [ ] `concurrency` 只作用在 `pull_request` 事件，group 以 PR 區分，develop 的 push 不受影響
  - [ ] 這張單本身的 PR CI 照常跑全套（PR 不跳過）；附 run 連結與 job 列表
  - [ ] 回報附改動前後的 `ci.yml` 差異說明，以及合併後如何確認（例：下一次 `ae docs commit` 的 run 只跑 security）
- **驗證指令**：`git diff --name-only <before> <after>` 模擬；`gh run view <id> --json jobs`
- **回報檔**：`.agent/reports/ci-docs-skip.json`

## 執行資訊（ae task 產生）

- Issue：#115
- 分支：`chore/ci-docs-skip`（從 develop 開出）
- 工作目錄：`.worktrees\ci-docs-skip`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
