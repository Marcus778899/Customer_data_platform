# 派工單 merge-cleanup-resume

- **類型**：chore
- **負責**：backend
- **目標**：`pr.py merge` 能續做已合併 PR 的本機清理；提供腳本清掉 `.worktrees/` 殘留資料夾；補回範本更新再次蓋掉的決策「版本」欄
- **背景**：D-066、D-065。PR #91 已在 GitHub 合併，但當時刪 worktree 時 Permission denied，後面的清理都沒做；現在重跑 merge 會停在 `gh pr merge`。`.worktrees/` 下 app-shell-login、DESIGN-01、FE-01、INIT-01 都已不是 git worktree，但資料夾還在；本機分支 `feat/app-shell-login` 也還在。91d2e9a 的範本更新又把 PR #90 補回的 `decision add` 四欄蓋掉，BOARD.md 最後一列 D-066 只有三欄（缺「版本」）；BOARD.md 開頭又出現「上限 80 行」註解（D-054 已取消）。修改以現行範本為基礎，不要整檔還原
- **設計依據**：`docs/decisions.md` D-054、D-065、D-066；PR #90 的 diff；`git show aa2bfc0^:scripts/board.py`
- **相依**：無
- **負責檔案**：
  - `scripts/pr.py`
  - `scripts/board.py`
  - `scripts/_common.py`
- **禁區**：BOARD.md、docs/、tasks/、templates/、.claude/、其他 scripts；不改其他指令的既有行為；不可讀 .env；實測只在暫存 git repo（無 origin，必要時以替身模擬 gh）
- **驗收測試**：無（chore；由 qa 以暫存 repo 實測）
- **驗收條件**：
  - [ ] PR 已合併（或 meta 已標 merged）時，`pr.py merge` 跳過合併閘門與 `gh pr merge`，只續做：更新 develop、刪 worktree（已不是 worktree 就刪殘留資料夾）、刪本機分支、標記已合併；重跑多次結果相同
  - [ ] 刪不掉時列出卡住的路徑與原因，並提示「關掉佔用的程式或重開機後重跑同一指令」；可以續做的部分照樣做完
  - [ ] 有腳本方式列出並清掉 `.worktrees/` 下 git 不認得、也不屬於進行中派工單的資料夾，可以先只列出不刪
  - [ ] `board.py decision add <D-編號> "<摘要>" [--version vX.Y.Z]` 寫四欄，未指定版本取「目標與範圍」本輪版本；能補正既有缺版本欄的列（用目前 BOARD.md 的副本實測，附 diff）；`board.py check` 會抓出欄數不對的列
  - [ ] 新布告欄（archive 產生的）不再帶「上限 80 行」註解
- **驗證指令**：暫存 repo 實測輸出；`python3 scripts/board.py check`；`make check`
- **回報檔**：`reports/merge-cleanup-resume.json`

## 執行資訊（task.py 產生）

- Issue：#93
- 分支：`chore/merge-cleanup-resume`（從 develop 開出）
- 工作目錄：`.worktrees\merge-cleanup-resume`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
