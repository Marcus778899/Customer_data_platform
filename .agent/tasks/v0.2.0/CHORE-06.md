# 派工單 CHORE-06

- **類型**：chore
- **負責**：backend
- **目標**：`scripts/board.py` 新增「封存已完成任務」指令，把狀態為 done 的任務列從布告欄移除
- **背景**：D-035。布告欄上限 80 行（`MAX_LINES`），board.py 只能新增不能移除，v0.1.0 的 21 張 done 單已占滿，v0.2.0 無法再加任務與決策。done 單的紀錄仍在 `tasks/`、`reports/` 與 GitHub，布告欄不需保留。
- **設計依據**：`scripts/board.py` 開頭說明與既有指令寫法；CLAUDE.md「只有 manager 可以改 BOARD.md（透過 scripts/board.py）」
- **相依**：無
- **負責檔案**：
  - `scripts/board.py`
- **禁區**：BOARD.md（不可直接改，也不可在 worktree 執行新指令後提交 BOARD.md）、其他 scripts、templates/、api/、web/、docs/、Makefile；不可讀 .env
- **驗收測試**：無（chore；由 qa 以 BOARD.md 副本實測）
- **驗收條件**：
  - [ ] 新指令（例：`board.py task archive`）移除「任務狀態」表中所有 status=done 的列，保留表頭、分隔線與其他狀態的列；表內只剩表頭時表格仍合法（`board.py check` 通過、之後 `task set` 可正常新增）
  - [ ] 輸出移除了哪些任務 ID；沒有 done 列時輸出說明並以 0 結束
  - [ ] 其他區塊（目標與範圍、已定案決策、卡住、注意事項）完全不變
  - [ ] 開頭用法說明加上新指令；既有指令行為不變
  - [ ] 以 BOARD.md 的副本實測（可用環境變數或參數指定路徑，或在暫存目錄複製整個 scripts 執行；不得改到主目錄或 worktree 的 BOARD.md），附前後行數與 diff
- **驗證指令**：`python3 scripts/board.py check`；以副本執行新指令前後的 diff；`make check`
- **回報檔**：`reports/CHORE-06.json`

## 執行資訊（task.py 產生）

- Issue：#70
- 分支：`chore/chore-06`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-06`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（甲方修正 D-035 做法，取代原「只移除 done 列」）：
  - 指令改為帶版本：例 `board.py task archive <vX.Y.Z>`。先把**當下整份 BOARD.md 原樣**寫到 `tasks/job_history/<vX.Y.Z>.md`（檔案開頭加一行註明封存日期與版本即可，其餘內容不改），再從布告欄移除所有 done 任務列。
  - 版本格式不對 → exit 1；`tasks/job_history/<版本>.md` 已存在 → 拒絕覆寫、exit 1、布告欄不變；寫快照失敗時布告欄不可被修改。
  - `scripts/docs.py` 的 `DOC_PATHS` 加入 `tasks/job_history/`，開頭說明同步更新，讓 manager 能用 docs.py 提交快照。
- 負責檔案追加：`scripts/docs.py`
- 驗收條件追加：
  - [ ] 以副本實測：快照檔內容與執行前 BOARD.md 相同（除開頭註明行），布告欄移除 done 列，其他區塊不變
  - [ ] 重複對同一版本執行被拒絕且不改任何檔案
  - [ ] docs.py 的 DOC_PATHS 含 `tasks/job_history/`（只讀程式碼確認即可，不在主目錄實際提交）
