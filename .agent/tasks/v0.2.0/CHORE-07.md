# 派工單 CHORE-07

- **類型**：chore
- **負責**：backend
- **目標**：已發版的派工單依版本歸檔到 `tasks/<版本>/`，並讓歸檔目錄進 git
- **背景**：D-039、D-040（取代 D-035 的 `tasks/job_history/` 位置）。目前 `.gitignore` 忽略整個 `tasks/`；`board.py task archive` 只寫 `tasks/job_history/<版本>.md`。v0.1.0 的快照已在 `tasks/job_history/v0.1.0.md`，派工單散在 `tasks/` 根目錄。主目錄的 `tasks/` 是本機忽略檔，worker 不可動，搬移由 manager 合併後在主目錄執行新指令完成。
- **設計依據**：`scripts/board.py`、`scripts/docs.py`、`scripts/_common.py`（`TASKS_DIR`、`load_meta`）開頭說明；`docs/decisions.md` D-039、D-040
- **相依**：無
- **負責檔案**：
  - `scripts/board.py`
  - `scripts/docs.py`
  - `scripts/init_project.py`
  - `.gitignore`
- **禁區**：BOARD.md、主目錄 `tasks/`（不可搬、不可刪，只能用暫存副本實測）、其他 scripts、templates/、api/、web/、docs/；不可讀 .env
- **驗收測試**：無（chore；由 qa 以暫存副本實測）
- **驗收條件**：
  - [ ] `board.py task archive <版本> <代稱>`：快照寫到 `tasks/<版本>/<代稱>.md`（代稱限小寫英數與 `-`）；把 `tasks/` 根目錄中 meta 為已合併或已作廢的 `<id>.md`、`<id>.meta.json`，以及 `release-<版本>.json` 搬進 `tasks/<版本>/`；再移除布告欄 done 列。輸出搬了哪些檔
  - [ ] 目標快照已存在、版本或代稱格式錯 → exit 1，不改任何檔案；未合併、未作廢的派工單留在根目錄
  - [ ] 遷移：若 `tasks/job_history/<版本>.md` 存在，改用它當快照（搬過去、改名為代稱），且不改 BOARD.md；`job_history/` 變空就刪掉
  - [ ] `.gitignore`：`tasks/<版本>/` 進 git，`tasks/` 根目錄的進行中派工單仍忽略（`git check-ignore` 實測兩種路徑）；`init_project.py` 的 `.gitignore` 內容同步
  - [ ] `docs.py` 能提交 `tasks/<版本>/`（取代 `tasks/job_history/`），開頭說明同步；board.py 開頭說明同步
  - [ ] 以暫存目錄副本實測（`BOARD_FILE`、tasks 路徑可用環境變數指定），附遷移前後的 `ls -R` 與 BOARD.md diff
- **驗證指令**：`python3 scripts/board.py check`；暫存副本實測輸出；`make check`
- **回報檔**：`reports/CHORE-07.json`

## 執行資訊（task.py 產生）

- Issue：#78
- 分支：`chore/chore-07`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-07`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-19

- 追加（architect review changes，2026-10-19）：
  1. 歸檔要分版本：讀 `release-<版本>.json` 的 `pr`，只搬 meta 中 `pr`（無 pr 時用 `issue`）小於它的已合併／已作廢單；release 檔不存在就 exit 1、不改任何檔。實測加一張「發版後才合併」的單（例：CHORE-06 情境，PR #71 > #69），確認留在根目錄
  2. 失敗回滾：任何一步失敗時反向還原（已搬檔案搬回、刪新快照；遷移模式把快照改回 `job_history/<版本>.md`）；`job_history/` 等全部搬完才刪；撞名檢查含快照檔名、不分大小寫，且在寫任何檔之前完成。實測用模擬失敗（例：目標同名檔或唯讀）證明回滾
  3. `init_project.py`：既有 .gitignore 中完全等於 `tasks/` 的行換成 `tasks/*`，再補缺的行，並印出已替換
  4. 併入 CHORE-08（D-054）：移除 BOARD.md 行數上限（`MAX_LINES`），開頭說明同步；決策表加「版本」欄，`decision add` 可指定版本（未指定取「目標與範圍」本輪版本號），提供一次性補欄：D-001～D-032 標 v0.1.0、D-033 起標 v0.2.0；以副本實測超過 80 行寫入與補欄
  5. make check 要跑完（Maven 約 10 分鐘，請在背景跑完再回報）；api 的 DeliveryResilienceAcceptanceTest 不穩定由 FIX-09 處理，若只有它失敗請在回報註明
