# Customer Data Platform

<!-- manager 維護。給第一次打開專案的人看：這是什麼、怎麼跑起來、現在做到哪 -->

客戶資料平台＋行銷自動化：整合各資料來源並歸戶、圈出受眾、讓客戶走進可編排的旅程（等待、分支、動作），並透過 LINE、Email 等通道發送與回收成效。給品牌的行銷人員使用；由團隊代管，每家客戶一套獨立環境（D-051）。

## 快速開始

Windows 請在 **Git Bash** 執行以下指令（Makefile 需要 sh；PowerShell／cmd 會失敗）。

1. 設定：`.claude/skills/agent-engineering/bin/ae check-env`，依提示在 `.env` 填入缺少的值（clone 後先 `git submodule update --init`）
2. 安裝：`make setup`（需 Temurin 17、Node 24）；外部服務（需 Docker Desktop，起 PostgreSQL 與 RabbitMQ）：
   `docker compose --env-file .env -f docker-compose.yml up -d --wait`
3. 啟動：後端 `make api-run`、前端 `make web-dev`（各開一個終端機）
4. 示範資料：後端啟動後 `make demo-seed`，會印出可在軌跡頁查詢的客戶 ID（D-031）
5. 檢查（lint＋不需容器的測試）：後端 `cd api && ./mvnw -B -q spotless:check && ./mvnw -B -Punit-only verify`；前端 `npm run lint -w web && npm test -w web`
6. 後端全部測試（含驗收，需 Docker）：
   `docker compose --env-file .env -f compose.yaml run --rm test`
7. 停止外部服務：
   `docker compose --env-file .env -f docker-compose.yml down`

主目錄有 `compose.yaml`（測試）與 `docker-compose.yml`（外部服務）兩份，手動下 `docker compose` 一律帶 `-f` 指定。

## 功能

| 功能 | 版本 | 說明 |
|---|---|---|
| 事件攝取 API（Bearer 金鑰，`event_id` 冪等） | v0.1.0 | D-019；契約 `docs/contracts/journey-engine.md` §3.2 |
| 旅程引擎直線流程：事件 → Enrollment → 等待 → 喚醒 → 發送 → 結束，可控時鐘、節點軌跡 | v0.1.0 | D-004；派發失敗退避重試，用完記錄放棄（D-024、D-026、D-027） |
| Mock 發送管線：outbox → RabbitMQ → 冪等 consumer，中斷重啟恰好一則 | v0.1.0 | D-006 |
| 唯讀 Enrollment 軌跡查詢頁：客戶 ID／Enrollment ID＋狀態篩選、節點軌跡、發送歷史、個資白名單遮罩、本地時區／UTC | v0.1.0 | D-005、D-013～D-016、D-020、D-021；規格 `docs/ui/enrollment-trace.md` |

## 架構

| 目錄 | 用途 |
|---|---|
| `api/` | 後端：Java 17＋Spring Boot 4.1（Maven） |
| `web/` | 前端：React 19＋TypeScript＋Vite |
| `docs/spec/` | 產品規格（事件契約、Enrollment、旅程定義、節點、發送管線、Segment DSL、資料表） |
| `docs/design/` | 各設計單的設計文件（技術棧與分期見 `docs/design/INIT-01.md`） |
| `docker-compose.yml` | 本機 PostgreSQL 18、RabbitMQ 4.3 |
| `.claude/skills/agent-engineering/` | 團隊流程工具（git submodule，`ae` 指令：派工、PR、發版） |
| `.agent/` | 團隊流程狀態：布告欄、派工單、回報 |

## 開發方式

由 agent 團隊開發（[agent-engineering](https://github.com/Marcus778899/agent-engineering)，規則見 `CLAUDE.md`；`claude --agent manager` 開始）。分支：`main` ← `develop` ← `feat/…`、`fix/…`；每個階段合回 `main` 並打 tag。
決策紀錄：`docs/decisions.md`；使用者測試：`docs/uat/`。

## 版本

| 版本 | 日期 | 內容 |
|---|---|---|
| v0.1.0 | 2026-10-18 | 旅程引擎直線流程、Mock 發送、事件攝取、唯讀軌跡查詢頁（UAT：`docs/uat/v0.1.0.md`；已知小問題見 GitHub `backlog` 標籤） |

## 授權

保留所有權利，見 `LICENSE`。
