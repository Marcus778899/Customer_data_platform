# 派工單 CHORE-09

- **類型**：chore
- **負責**：backend
- **目標**：`scripts/` 在 Windows（中文系統 cp950）下讀寫中文不出錯，不必另設 `PYTHONIOENCODING`
- **背景**：2026-10-19 manager 在 Windows Git Bash 實測兩個錯誤。1) `docs.py commit` 中文訊息：`_common.run()` 的 `subprocess.run(text=True)` 用 cp950 解碼 git 輸出 → `UnicodeDecodeError`，stdout 變 None → `AttributeError`（commit 其實已成功，但腳本 exit 1）。2) `task.py amend` 從 stdin 讀中文 heredoc 被當 cp950 解出 surrogate，寫檔時 `UnicodeEncodeError`（未寫入）。設 `PYTHONIOENCODING=utf-8 PYTHONUTF8=1` 後兩者正常。
- **設計依據**：`scripts/_common.py`、`scripts/task.py`、`scripts/pr.py`、`scripts/release.py` 開頭說明
- **相依**：無
- **負責檔案**：
  - `scripts/_common.py`
  - `scripts/task.py`
  - `scripts/pr.py`
  - `scripts/release.py`
  - `scripts/docs.py`
  - `scripts/board.py`
  - `scripts/check_env.py`
  - `scripts/init_project.py`
  - `scripts/guard_paths.py`
  - `scripts/validate_report.py`
- **禁區**：BOARD.md、docs/、tasks/、templates/、api/、web/、.claude/；不改腳本的指令介面與既有行為；不可讀 .env；不可用真的 GitHub repo 或主目錄實測會開 issue／PR／commit 的指令（用暫存 git repo、無 origin）
- **驗收測試**：無（chore；由 qa 以暫存 repo 實測）
- **驗收條件**：
  - [ ] `run()`／`run_status()` 等所有 subprocess 呼叫明確以 UTF-8 解碼（`encoding="utf-8"`，無法解碼時不崩潰，例 `errors="replace"`），stdout 不會是 None
  - [ ] 所有讀 stdin 的地方（task.py、pr.py、guard_paths.py、validate_report.py 等）以 UTF-8 讀取；印到 stdout／stderr 的中文在 cp950 主控台不崩潰
  - [ ] 不設任何 PYTHON* 環境變數下，在暫存 git repo 實測：中文訊息 `docs.py commit` exit 0；`task.py amend` 以中文 heredoc 追加後檔案內容正確（附 `tail` 輸出）
  - [ ] 全部腳本 grep 確認沒有遺漏的 `subprocess.run(... text=True)` 未指定 encoding、`sys.stdin.read()` 未設定 UTF-8
- **驗證指令**：暫存 repo 實測輸出；`python3 scripts/board.py check`；`make check`
- **回報檔**：`reports/CHORE-09.json`

## 執行資訊（task.py 產生）

- Issue：#85
- 分支：`chore/chore-09`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-09`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
