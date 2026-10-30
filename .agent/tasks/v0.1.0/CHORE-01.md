# 派工單 CHORE-01

- **類型**：chore
- **負責**：architect
- **目標**：把 agent 專案記憶目錄加進 .gitignore
- **背景**：D-012：`.claude/agent-memory/` 只留本機，不進版控。
- **設計依據**：docs/decisions.md D-012
- **相依**：無
- **負責檔案**：
  - `.gitignore`
- **禁區**：其他所有檔案；不可刪除或修改 .claude/agent-memory/ 內容
- **驗收測試**：無
- **驗收條件**：
  - [ ] `.gitignore` 含 `.claude/agent-memory/`
  - [ ] 主目錄 `git status --short` 不再列出 .claude/agent-memory/
- **驗證指令**：`git check-ignore .claude/agent-memory/architect/MEMORY.md && make check`
- **回報檔**：`reports/CHORE-01.json`

## 執行資訊（task.py 產生）

- Issue：#5
- 分支：`chore/chore-01`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-01`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-17

## 追加 1（2026-10-18）

- 同一個 `.gitignore` 一併修正：`.venv/`、`node_modules/` 改成不加結尾斜線（`.venv`、`node_modules`），讓 worktree 裡的 symlink（D-011）也被忽略。
- 驗收條件追加：在 worktree 跑 `git status --short` 不再列出 `.venv`、`node_modules`。
- 驗收條件 2（主目錄 git status）改為合併後由 manager 確認，不算本單驗收。
