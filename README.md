# Customer Data Platform

<!-- manager 維護。給第一次打開專案的人看：這是什麼、怎麼跑起來、現在做到哪 -->

多租戶的客戶資料平台＋行銷自動化：圈出受眾、讓客戶走進可編排的旅程（等待、分支、動作），並透過 LINE、Email 等通道發送與回收成效。給品牌的行銷人員使用。

## 快速開始

1. 設定：`python3 scripts/check_env.py`，依提示在 `.env` 填入缺少的值
2. 安裝：`make setup`（需 Temurin 17、Node 24）；外部服務 `make infra-up`（需 Docker Desktop，起 PostgreSQL 與 RabbitMQ）
3. 啟動：後端 `make api-run`、前端 `make web-dev`
4. 檢查：`make check`

## 功能

| 功能 | 版本 | 說明 |
|---|---|---|
| （開發中）旅程引擎直線流程＋唯讀軌跡頁 | v0.1.0 | 範圍見 D-004、D-005 |

## 架構

| 目錄 | 用途 |
|---|---|
| `api/` | 後端：Java 17＋Spring Boot 4.1（Maven） |
| `web/` | 前端：React 19＋TypeScript＋Vite |
| `docs/spec/` | 產品規格（事件契約、Enrollment、旅程定義、節點、發送管線、Segment DSL、資料表） |
| `docs/design/` | 各設計單的設計文件（技術棧與分期見 `docs/design/INIT-01.md`） |
| `docker-compose.yml` | 本機 PostgreSQL 18、RabbitMQ 4.3 |
| `scripts/` | 團隊流程腳本（派工、PR、發版） |

## 開發方式

由 agent 團隊開發（規則見 `CLAUDE.md`）。分支：`main` ← `develop` ← `feat/…`、`fix/…`；每個階段合回 `main` 並打 tag。
決策紀錄：`docs/decisions.md`；使用者測試：`docs/uat/`。

## 版本

| 版本 | 日期 | 內容 |
|---|---|---|

## 授權

保留所有權利，見 `LICENSE`。
