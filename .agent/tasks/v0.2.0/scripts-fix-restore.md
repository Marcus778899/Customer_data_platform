# 派工單 scripts-fix-restore

- **類型**：chore
- **負責**：backend
- **目標**：在範本更新（aa2bfc0）後的 `scripts/` 上補回兩項專案修正：中文編碼、決策表「版本」欄
- **背景**：D-065。範本同步把 PR #86（CHORE-09）的編碼修正與 D-054 的決策版本欄蓋掉；歸檔不補回，改用範本的 `board.py archive`。BOARD.md 已定案決策表目前是四欄（日期｜編號｜版本｜決策），現在的 `decision add` 只寫三欄會破壞表格。以範本新版為基礎修改，不要整檔還原舊版。
- **設計依據**：PR #86 的 diff（`git show 8fbc089`）；aa2bfc0 前的 `scripts/board.py`（`git show aa2bfc0^:scripts/board.py` 的 decision add／backfill-version）；`docs/decisions.md` D-054、D-065
- **相依**：無
- **負責檔案**：
  - `scripts/_common.py`
  - `scripts/board.py`
  - `scripts/guard_paths.py`
  - `scripts/validate_report.py`
- **禁區**：BOARD.md、docs/、tasks/、templates/、.claude/、其他 scripts；不改其他指令介面與範本新功能（archive、單號規則）；不可讀 .env；實測只在暫存 git repo（無 origin）
- **驗收測試**：無（chore；由 qa 以暫存 repo 實測）
- **驗收條件**：
  - [ ] 編碼：與 PR #86 等效（subprocess 指定 utf-8＋errors=replace、stdin/stdout/stderr reconfigure 成 UTF-8）；不設 PYTHON* 變數下中文 `docs.py commit`、`task.py amend` 正常
  - [ ] `board.py decision add <D-編號> "<摘要>" [--version vX.Y.Z]` 寫四欄，未指定版本取「目標與範圍」本輪版本；開頭說明同步
  - [ ] `board.py archive` 封存後新布告欄的決策表仍是四欄、沿用全部決策
  - [ ] 對目前 BOARD.md 的副本（`BOARD_FILE` 或等效方式）實測 `check`、`decision add`，附 diff
- **驗證指令**：暫存 repo 實測輸出；`python3 scripts/board.py check`；`make check`
- **回報檔**：`reports/scripts-fix-restore.json`

## 執行資訊（task.py 產生）

- Issue：#89
- 分支：`chore/scripts-fix-restore`（從 develop 開出）
- 工作目錄：`.worktrees\scripts-fix-restore`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
