# 派工單 INIT-01

- **類型**：docs
- **負責**：architect
- **目標**：定出專案技術棧與開發基礎設施（Makefile、.env.example、CI 安裝步驟）
- **背景**：新專案 Customer Data Platform（CDP），repo 剛初始化，尚無程式碼。
  甲方尚未給詳細需求；技術棧選項與取捨請列成「待決定」，由 manager 一次問甲方。
  開發環境為 Windows（Git Bash），Makefile 與腳本需能在此執行；worktree 共用主目錄的 .venv／node_modules／.env。
- **設計依據**：templates/design.md、.github/workflows/ci.yml、CLAUDE.md
- **相依**：無
- **負責檔案**：
  - `docs/design/INIT-01.md`
  - `Makefile`
  - `.env.example`
  - `.github/workflows/ci.yml`
- **禁區**：scripts/、templates/、BOARD.md、README.md、docs/decisions.md；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] 設計文件說明技術棧、目錄結構、驗收測試目錄慣例，待決定以清單列出
  - [ ] Makefile 至少有 setup、check 目標，`make check` 在空專案可執行通過
  - [ ] .env.example 只列變數名稱與說明，不含實際值
  - [ ] CI 安裝步驟與 Makefile 一致
- **驗證指令**：`make setup && make check`
- **回報檔**：`reports/INIT-01.json`

## 執行資訊（task.py 產生）

- Issue：#1
- 分支：`docs/init-01`（從 develop 開出）
- 工作目錄：`.worktrees\INIT-01`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-17

## 追加（2026-10-18，甲方新方向，取代原「技術棧未定」的前提）

- 甲方已決定 D-001～D-003（見 docs/decisions.md）：後端 Java Spring Boot、前端 React；產品依 `../CDP/docs/plan/backbone-plan.md` 與 `../CDP/docs/spec/` 發想。
- 甲方表示計劃書裡的架構可能有誤解：請批判性審視，在設計文件列出「沿用／修正／延後」與理由；需要甲方拍板的列為待決定。`../CDP/backend`、`../CDP/frontend` 是先前的嘗試，可參考能否沿用。
- 版本由團隊定（D-003）：請提出 Java（本機僅 Temurin 17）、Spring Boot、Node、React 等版本；以及分期與版號的對應（每個階段 = vX.Y.0），第一個階段的範圍要小、可驗收。
- 移除 Python／FastAPI 的假設：Makefile、CI 改為 Gradle 或 Maven（擇一並說明）＋ npm；目錄慣例與驗收測試目錄依新技術棧重訂。
- 需要外部服務（PostgreSQL、Redis、RabbitMQ…）時以 docker compose 提供，設定值只在 `.env.example` 列變數名稱。
- 追加負責檔案：`.gitattributes`（Makefile、*.sh、gradlew 設 eol=lf）；如需 `docker-compose.yml`、建置設定檔骨架（如 `build.gradle`、`web/package.json`）也可加入，請在回報列出。
- 計劃書與 spec 是否複製進本 repo 的 `docs/`：請提建議（列待決定）。

## 追加 2026-10-17

## 追加 2（2026-10-18，需調查結果）

- 調查結果：主目錄 `docs/research/INIT-01-research.md`（未進版控，請讀主目錄那份；不要把它加進本分支，需要的事實摘要寫進設計文件並附來源）。
- 依結果修訂：Spring Boot 4.1.1 確認為最新、Java 17 仍為最低需求；Temurin 17 EOL 2027-10-31，請把 Java 版本待決定補上時程依據；Testcontainers 用 2.0.5，Docker 29 API 1.44 的繞法寫進設計（實測待 Docker 啟動）；TypeScript 維持 6.0 的理由補上（npm latest 為 7.0.2）。
- 追加負責檔案：`.gitignore`（加 `api/target/`、`web/dist/`）。
- 待決定請依 docs/decisions.md 範本格式整理在設計文件末段，manager 會直接搬過去。

## 追加 2026-10-17

## 追加 3（2026-10-18）

- 待決定已搬進 docs/decisions.md（D-004～D-009，編號沿用你的提案）。設計文件的待決定區塊改成一行引用 D-004～D-009，讓文件回到 60 行內。

## 追加 2026-10-17

## 追加 4（2026-10-18，甲方回覆 D-004～D-009，見 docs/decisions.md）

- D-004、D-005、D-007、D-009 照建議定案。
- D-006 改為採 RabbitMQ（甲方預期大量非同步旅程實例）：請修訂設計——RabbitMQ 的角色、引入時點（建議與 v0.1.0 的 outbox relay 一起，若有理由延後請寫明）、docker compose 服務、相依、分期表；守住計劃書 7.2.1 三條紅線（長等待不用 MQ 延遲訊息、入列經 outbox、consumer 冪等）。修正 R3 的結論。
- D-008：把 `../CDP/docs/spec/` 全部複製到 `docs/spec/`，設計文件改引用本 repo 路徑；計劃書只引用、不複製。
- 追加負責檔案：`docs/spec/**`、`LICENSE`（LICENSE 等著作權人名稱 D-010 定案後另行追加，本次先不做）。

## 追加 2026-10-17

## 追加 5（2026-10-18，D-010 定案）

- 新增 `LICENSE`：以 `../CDP/LICENSE` 為範本，第一行改為 `Copyright (c) 2026 Marcus Lin. All Rights Reserved.`，刪掉「PROPRIETARY AND CONFIDENTIAL」中的 CONFIDENTIAL（改為 `PROPRIETARY`），其餘條文不變。

## 追加 2026-10-17

## 追加 6（2026-10-18）

- 依 D-010 的意旨（刪掉與 public repo 矛盾的 confidential），LICENSE 第 6 行的「proprietary and confidential property」改為「proprietary property」，其他不動。
