# 派工單 CHORE-08

- **類型**：chore
- **負責**：backend
- **目標**：`scripts/board.py` 取消 BOARD.md 行數上限，已定案決策表加上所屬版本
- **背景**：D-054。布告欄已 80 行，D-050 寫不進去（`MAX_LINES`）。決策表要維持完整（D-035），改用版本區分。CHORE-07 也改 board.py（PR #79，qa 中），本單等它合併後從 develop 開始改。
- **設計依據**：`scripts/board.py` 開頭說明；`docs/decisions.md` D-035、D-054
- **相依**：CHORE-07 合併
- **負責檔案**：
  - `scripts/board.py`
- **禁區**：主目錄 BOARD.md（只能用副本實測）、其他 scripts、templates/、api/、web/、docs/；不可讀 .env
- **驗收測試**：無（chore；由 qa 以副本實測）
- **驗收條件**：
  - [ ] 移除行數上限（寫入與 `check` 都不再因行數失敗），開頭說明同步
  - [ ] 決策表加「版本」欄；`decision add` 可指定版本（未指定時取「目標與範圍」本輪的版本號）
  - [ ] 提供一次性補欄方式：既有列 D-001～D-032 標 v0.1.0，D-033 起標 v0.2.0；其他區塊不變
  - [ ] 既有指令（scope、task set／archive、blocker、check）行為不變
  - [ ] 以 BOARD.md 副本實測補欄、新增決策、超過 80 行寫入，附前後 diff
- **驗證指令**：`python3 scripts/board.py check`；副本實測輸出；`make check`
- **回報檔**：`reports/CHORE-08.json`

## 執行資訊（task.py 產生）

- Issue：#82
- 分支：`chore/chore-08`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-08`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
