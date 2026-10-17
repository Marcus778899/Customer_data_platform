# 設計：專案初始化（INIT-01）

- 契約：無（尚無前後端介面）；技術棧待甲方決定，見下方「待決定」

## 做法

| # | 決策 | 理由 |
|---|---|---|
| 1 | Makefile 以 `git rev-parse --git-common-dir` 找主目錄，`.venv`、`.env` 一律用主目錄的 | worktree 共用主目錄環境（派工單背景） |
| 2 | `setup`／`lint`／`test` 依目錄是否存在決定執行或略過 | 空專案 `make check` 可通過；技術棧進來後不必改 Makefile |
| 3 | `ci-setup` 直接等於 `setup` | CI 與本機同一套安裝步驟 |
| 4 | Makefile recipe 只用 ASCII 訊息 | Windows 的 GNU Make 3.81 以系統 codepage 轉碼指令，UTF-8 中文會亂碼 |
| 5 | 匯出 `PYTHONUTF8=1` | Windows cp950 下 Python 讀寫 UTF-8 檔案出錯 |
| 6 | CI 維持 Python 3.12、Node 24 | 與本機一致（3.12.13、v24.22.0）；技術棧定案後只留用到的 |

## 目錄結構（技術棧定案後由設計任務建立）

| 路徑 | 內容 |
|---|---|
| `api/` | 後端；相依清單 `api/requirements.txt`（含 lint／測試工具），`make check` 對它跑 `ruff check` 與 `pytest` |
| `api/tests/unit/` | 後端 worker 的單元測試 |
| `api/tests/acceptance/` | 後端驗收測試：qa 寫、worker 不可改；`pytest api` 會跑到 |
| `web/` | 前端；有 `web/package.json` 時 `make check` 跑 `npm run lint`、`npm test` |
| `web/tests/unit/`、`web/tests/acceptance/` | 同後端慣例；`npm test` 必須涵蓋 acceptance |
| `docs/design/`、`docs/contracts/` | 設計與前後端契約 |
| `contracts/` | 只放流程 schema |

## 設定（寫進 .env.example，這裡不寫值）

- 無；服務 port、資料庫連線等由技術棧定案後的設計任務新增

## 檔案分工

| 檔案 | 負責 |
|---|---|
| `Makefile`、`.env.example`、`.github/workflows/ci.yml`、本文件 | architect（跨界檔案，後續變更也歸 architect） |

## 派工順序

1. 甲方回覆待決定 → 2. architect 設計第一個功能（建立 `api/`、`web/` 骨架與相依清單）→ 3. 前後端可平行（前端對契約 mock）→ 4. 整合單

## 待甲方決定／需要調查

- 待決定：
  1. 本輪目標與不做的範圍（BOARD 尚未設定）
  2. 後端語言／框架：A. Python + FastAPI ／ B. Node + Express ／ C. Python + Django；建議 A
  3. 資料庫：A. SQLite ／ B. PostgreSQL；建議依資料規模（需調查 1）
  4. 前端：A. 無 build 的 HTML/JS（Makefile 由 `.env` 產生設定檔）／ B. React + Vite + TypeScript ／ C. 本輪不做前端；建議 A
  5. 後端相依（若選 2A）：fastapi（框架、自動 OpenAPI）、uvicorn（ASGI 伺服器）、pytest（測試）、httpx（FastAPI TestClient 需要）、ruff（lint）；A. 全部核可 ／ B. 逐項刪減；建議 A
  6. 部署環境：A. 只在本機 ／ B. 單台主機 + Docker ／ C. 雲端代管服務；建議 A（本輪），上線需求明確後再議
- 需調查：
  1. 資料規模與來源系統（客戶數、事件量、來源格式／API）
  2. 客戶個資的法規要求（台灣個資法：保存期限、去識別化、刪除權）

## 風險

| 風險 | 對策 |
|---|---|
| 前端 `npm install` 在 worktree 內會產生 worktree 自己的 `node_modules` | 前端技術棧定案時，由設計任務決定共用方式（例：只在主目錄安裝） |
| `api/` 存在但 `.venv` 沒裝 ruff／pytest，`make check` 會失敗 | `api/requirements.txt` 必須列入 ruff、pytest |
