# 派工單 CHORE-05

- **類型**：chore
- **負責**：backend
- **目標**：新增 `make demo-seed`：對執行中的後端一次建好示範旅程與示範客戶，印出可在軌跡頁查詢的客戶 ID
- **背景**：D-031。甲方驗收時軌跡頁沒有資料、不知道要查哪一筆；目前只能照 docs/uat/v0.1.0.md 驗收清單用 curl 手動建旅程、送事件。
  只呼叫既有 API（契約 §3.1 建旅程、§3.2 送事件），不直接寫資料庫、不改後端程式。
- **設計依據**：docs/contracts/journey-engine.md §1.1、§3.1、§3.2；docs/spec/01-event-contract.md；docs/spec/03-journey-definition.md §2、§4；Makefile 開頭註解（Git Bash 與 Linux 皆可執行、recipe 只用 ASCII、D-029）
- **相依**：無
- **負責檔案**：
  - `tools/demo-seed/`（新增；腳本語言自選，限 Git Bash 與 CI Linux 已有的工具：sh、curl、python3）
  - `Makefile`（只新增 `demo-seed` target 與 help 一行，不改其他 target）
- **禁區**：`api/`、`web/`、`scripts/`、`templates/`、`docs/`、`.env.example`、`docker-compose.yml`；不可讀 .env
- **驗收測試**：無（chore；由 qa 對執行中的服務實測）
- **驗收條件**：
  - [ ] 後端已用 `make api-run` 啟動時，`make demo-seed` 成功建立：一個短等待旅程（數十秒內走完 → 已完成、有 1 筆已送出的發送）與一個長等待旅程（停在等待中）
  - [ ] 至少 3 位示範客戶，涵蓋「已完成」「等待中」「同一客戶有多筆 Enrollment」；事件 payload 含 email、phone 等欄位，可在軌跡頁看到遮罩效果
  - [ ] 結束時印出每位示範客戶的 profile_id、預期狀態，以及「多久後短旅程會變成已完成」的提示；可直接貼進軌跡頁查詢
  - [ ] 金鑰與網址只從環境變數讀取（`CDP_ADMIN_API_KEY`、`CDP_INGEST_API_KEY`、`API_PORT`，由 Makefile 從 .env 匯出）；輸出與錯誤訊息不得印出金鑰值
  - [ ] 後端沒啟動或金鑰錯誤時，印出一行清楚的原因（例：連不到 API_PORT、401）並以非 0 結束
  - [ ] 重複執行不會失敗（可建立新一批或沿用，行為在輸出說明清楚）
  - [ ] `make check` 通過；`make help` 列出 demo-seed
- **驗證指令**：`make check`；`make api-run`（以命令列變數覆寫金鑰與帳密）後執行 `make demo-seed`，再以 `GET /api/v1/enrollments?q=<印出的 profile_id>` 確認
- **回報檔**：`reports/CHORE-05.json`

## 執行資訊（task.py 產生）

- Issue：#67
- 分支：`chore/chore-05`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-05`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
