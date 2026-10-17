# 設計：專案初始化（INIT-01）

- 契約：無。產品依據 D-001、技術棧 D-002、版本授權 D-003；「計劃書」= `../CDP/docs/plan/backbone-plan.md`，「spec」= `../CDP/docs/spec/`

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 後端 Java 17（Temurin）+ Spring Boot 4.1.x；Maven + wrapper `api/mvnw`（3.9.16），不用 Gradle | 本機只有 JDK 17 與 Maven；Boot 4 基線即 17，3.x 已停 OSS 支援（需調查 1）；wrapper 讓 CI 與本機同版；../CDP 前次也是 Maven |
| 2 | 前端 React 19 + TypeScript 6.0 + Vite 8 + Vitest 5 + ESLint 10；Node 24 LTS | 本機 Node v24；TS 鎖 `~6.0` 因 typescript-eslint 只支援 <6.1 |
| 3 | 根 `package.json` 用 npm workspaces（`web`），`node_modules` 在 checkout 根目錄；Maven 相依在 `~/.m2` | `scripts/task.py` 只 symlink 根層 `node_modules` 進 worktree |
| 4 | PostgreSQL 18：本機 `docker-compose.yml`（`make db-up`，只綁 127.0.0.1），測試用 Testcontainers 同版映像 | 計劃書要求 16+；compose 只放當期用得到的服務 |
| 5 | `lint` = `mvnw spotless:check` + `eslint` + `tsc --noEmit`；`test` = `mvnw verify` + `vitest run`；`ci-setup` = `setup`；Makefile 自動 export `.env.example` 列的變數；recipe 只用 ASCII | CI 與本機同一套；新變數不必改 Makefile；Make 3.81 以 cp950 轉碼 |
| 6 | `.gitattributes` 讓 Makefile、`*.sh`、`api/mvnw`、原始碼 eol=lf；spotless 固定 UNIX 換行 | autocrlf 下 sh／make 遇 CRLF 會壞 |

## 目錄結構與檔案分工
| 路徑 | 內容 | 負責 |
|---|---|---|
| `api/src/main/java/com/cdp/<模組>/`、`api/src/test/java/com/cdp/<模組>/` | 後端程式與單元／整合測試；模組由各設計單定 | backend worker |
| `api/src/test/java/com/cdp/acceptance/`、`web/tests/acceptance/` | 驗收測試：類別名 `*AcceptanceTest`／檔名 `*.test.ts(x)`，`make test` 已涵蓋 | qa（worker 不可改） |
| `web/src/`、`web/tests/unit/` | 前端程式與單元測試 | frontend worker |
| `web/src/styles/tokens.css` | 設計 token（`web/src/main.tsx` 已載入） | designer |
| `Makefile`、`.env.example`、`.gitattributes`、`.github/workflows/ci.yml`、`docker-compose.yml`、`package.json`、`package-lock.json`、`api/pom.xml`、`api/mvnw*`、`api/.mvn/`、`api/src/main/resources/application.yml`、`web/package.json`、`web/{vite.config.ts,tsconfig.json,eslint.config.js}`、`docs/design/`、`docs/contracts/` | 跨界檔案與設計；加相依須經 architect 並列待決定 | architect |

## 設定（寫進 .env.example，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `API_PORT`、`WEB_PORT`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`／`POSTGRES_PASSWORD` | 後端與前端 dev server port（前端把 `/api` 轉給後端）；本機資料庫 | 僅密碼是 |

## 計劃書審視
| 結論 | 項目 | 理由／做法 |
|---|---|---|
| 沿用 | 定義／執行分離與版本綁定、節點純函式 + `requirements()`、Effect 由節點回傳、ArchUnit、Clock 注入、`decisionReason` 強制、outbox + 冪等鍵、三種非同步分類、JDBC 不用 JPA、logic-less 模板、spec 07 的 DDL 決策 | 已落成型別與約束，成本低；`../CDP/backend` 的 V2 migration、兩份 JSON Schema、三個場景 fixture 可搬（改 Boot 4／Testcontainers 2 套件名），V1 依 R2 重寫 |
| 修正 R1 | 計時器兩套：db-scheduler 與 `next_wake_at` + `lockDue … SKIP LOCKED` 重疊 | 只留後者，`Timer` = 在 `StateStore.save` 同一交易寫 `next_wake_at`。「不是輪詢全表」正解是「partial index 掃到期列」，任何 Postgres 計時器都是輪詢 |
| 修正 R2 | RLS 會靜默失效 | 本機 `POSTGRES_USER` 是 superuser、Flyway 同帳號建表成為擁有者，兩者都略過 RLS；`SET ROLE journey_engine` 持 app 憑證者皆可做；連線池中 `app.tenant_id` 非交易內設定會跨請求殘留。改：owner／app／engine 三 role（engine 獨立登入與連線池）、`FORCE ROW LEVEL SECURITY`、每交易 `set_config(…, true)`，驗收含「app role 讀不到他租戶」 |
| 修正 R3 | 基礎設施膨脹：以「多一套基礎設施是負擔」否決 Temporal，卻引入 RabbitMQ、Redis、db-scheduler、MinIO、Vault；RabbitMQ 仍要先寫 outbox | v0.1–v0.2 只用 Postgres，outbox 表即工作佇列（SKIP LOCKED、重試欄位、狀態即任務級可觀測性）；RabbitMQ 於 v0.3.0 重評（待決定 3） |
| 修正 R4 | Phase 1a「暫不處理冪等」違反不變量 #1，且 spec 04 `save()` 的交易形狀事後要改 | outbox 與冪等移入 v0.1.0（Mock 發送下成本極低） |
| 修正 R5 | 1b 的 ConditionNode 要讀 Profile 與資料字典，但 Profile 儲存排在 Phase 3（spec 07 §1） | v0.2.0 明列最小 `ProfileStore`（屬性 JSONB 表 + 寫入 API）與人工維護欄位清單 |
| 修正 R6 | 計劃書 7.4「事件表分區」與 spec 07 §6「不得分區」矛盾；Boot 3.x 已過時 | 依 spec，分區與 7 天去重同一次上線（v0.4.0）；Boot 改 4.1.x，Java 維持 17（待決定 4） |
| 修正 R7 | 畫布到 Phase 4 才有，前三期無 UI | v0.1.0 加唯讀 Enrollment 軌跡查詢頁（不變量 #6 的客服畫面），旅程先以 JSON API 建立（待決定 2） |
| 延後 | RabbitMQ、Redis（v0.3.0 頻率上限／kill switch）、MinIO、Vault/KMS、Kafka、Temporal（換路條件照計劃書 7.3）、OpenTelemetry 全套（先 actuator + 結構化日誌）、React Flow、TanStack Query／Zustand、分析型儲存 | 用到的那一期由設計單列相依待決定 |

## 分期與版號（每階段 = vX.Y.0，修補 = vX.Y.Z）
| 版號 | 計劃書 | 範圍 | 驗收 |
|---|---|---|---|
| v0.1.0 | 1a（縮） | 事件攝取 API（單租戶，欄位帶 `tenant_id`）→ Start／EventSource／WaitDelay／SendMessage(Mock)／End；StateStore、計時器（R1）、outbox + 冪等（R4）、Clock、節點軌跡；軌跡查詢頁（待決定 2） | 灌事件 → 可控時鐘快轉 → Mock 訊息 → `COMPLETED`；「狀態已提交、Effect 未執行」時中斷重啟，Mock 訊息恰好一則 |
| v0.2.0 | 1b | WaitForEvent／Condition／SplitTest／Exit、G1、G3、版本綁定、RLS（R2）、最小 ProfileStore（R5） | 附錄 C 場景一；同 `cart_id` 觸發 5 次只建 1 個 Enrollment |
| v0.3.0／v0.4.0／v0.5.0 | 2／3／4 | 依計劃書第六節，各期開工前出設計單 | 同計劃書各期 |
| v1.0.0 | — | 首次正式上線，條件由甲方定 | — |

## 派工順序
1. 甲方回覆待決定 → 2. architect 設計 v0.1.0（契約、骨架、migration 自 `../CDP` 搬入）→ 3. backend 與 frontend（對契約 mock）平行，qa 同時寫驗收 → 4. 整合單

## 待甲方決定／需要調查
- 待決定：1 本輪範圍＝v0.1.0（上表）；2 v0.1.0 含唯讀軌跡頁 A. 含／B. 只做 API，建議 A；3 C 類任務 A. 先用 Postgres outbox、v0.3.0 重評／B. v0.3.0 上 RabbitMQ／C. JobRunr，建議 A；4 Java A. 維持 17／B. 裝 Temurin 25 LTS 後升級，建議 A、v0.3.0 前再議；5 計劃書／spec 進 repo A. spec 複製到 `docs/spec/` 由本 repo 維護、計劃書只引用／B. 兩者複製為唯讀參考／C. 不複製，建議 A；6 後端相依、7 前端相依核可（清單見回報）
- 需調查：1 Spring Boot 3.5／4.1 支援期；2 Testcontainers 2 在 Docker Engine 29+／Windows 是否仍需 ../CDP 的 api.version 繞法；3 npm 經 worktree 的 `node_modules` symlink 寫入是否正常

## 風險
| 風險 | 對策 |
|---|---|
| 本機 Docker daemon 未啟動，Testcontainers 測試跑不了；worktree 跑 `npm ci` 會重建共用 `node_modules`；`api/target/`、`web/dist/` 未被 `.gitignore` 排除 | v0.1.0 開工前確認 Docker（需調查 2）；改相依只在單一任務內做、合併後主目錄重跑 `make setup`；`.gitignore` 請 manager 補（不在本單負責檔案） |
