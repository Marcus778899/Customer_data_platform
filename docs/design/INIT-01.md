# 設計：專案初始化（INIT-01）

- 契約：無。產品依據 D-001、技術棧 D-002、版本授權 D-003；「計劃書」= `../CDP/docs/plan/backbone-plan.md`，「spec」= `../CDP/docs/spec/`；版本事實見文末「來源」[S1]–[S7]（查詢日 2026-10-18）

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 後端 Java 17（Temurin）+ Spring Boot 4.1.1；Maven 3.9.16 + wrapper `api/mvnw`，不用 Gradle | 4.1.1 為 4.1 線最新、最低 Java 17 [S1][S2]；3.5 OSS 已於 2026-06-30 結束 [S1]；Maven 4 仍為 rc [S6]；本機只有 JDK 17 與 Maven；../CDP 前次也是 Maven |
| 2 | 前端 React 19 + TypeScript `~6.0.3` + Vite 8 + Vitest 5 + ESLint 10；Node 24 LTS | npm latest TS 為 7.0.2，但 typescript-eslint 只支援 TS `>=4.8.4 <6.1.0` [S5]，升 7 會讓 lint 失去支援；Vitest／ESLint engines 皆含 Node 24 [S5] |
| 3 | 根 `package.json` 用 npm workspaces（`web`），`node_modules` 在 checkout 根目錄；Maven 相依在 `~/.m2` | `scripts/task.py` 只 symlink 根層 `node_modules` 進 worktree |
| 4 | PostgreSQL 18：本機 `docker-compose.yml`（`make db-up`，只綁 127.0.0.1）；整合測試 Testcontainers 2.0.5 同版映像 | 計劃書要求 16+，18 支援至 2030-11-14 [S7]；Testcontainers 1.x 不修 Docker 29，只有 2.x 修 [S3] |
| 5 | Docker 29（最低 API 1.44）相容：先不加繞法；若 2.0.5 仍協商失敗，加 `api/src/test/resources/docker-java.properties` 的 `api.version=1.44`（只影響測試 classpath，不用 `System.setProperty` 全域設定、不要求改 daemon） | 2.0.3 起已改協商 [S3]；`api.version` 是社群繞法、官方文件未提 [S4]；實測待本機 Docker daemon 啟動（需調查 2） |
| 6 | `lint` = `mvnw spotless:check` + `eslint` + `tsc --noEmit`；`test` = `mvnw verify` + `vitest run`；`ci-setup` = `setup`；Makefile 自動 export `.env.example` 列的變數；recipe 只用 ASCII | CI 與本機同一套；新變數不必改 Makefile；Make 3.81 以 cp950 轉碼 |
| 7 | `.gitattributes` 讓 Makefile、`*.sh`、`api/mvnw`、原始碼 eol=lf；`.gitignore` 排除 `api/target/`、`web/dist/` | autocrlf 下 sh／make 遇 CRLF 會壞；建置產物不進版控 |

## 目錄結構與檔案分工
| 路徑 | 內容 | 負責 |
|---|---|---|
| `api/src/main/java/com/cdp/<模組>/`、`api/src/test/java/com/cdp/<模組>/` | 後端程式與單元／整合測試；模組由各設計單定 | backend worker |
| `api/src/test/java/com/cdp/acceptance/`、`web/tests/acceptance/` | 驗收測試：類別名 `*AcceptanceTest`／檔名 `*.test.ts(x)`，`make test` 已涵蓋 | qa（worker 不可改） |
| `web/src/`、`web/tests/unit/` | 前端程式與單元測試 | frontend worker |
| `web/src/styles/tokens.css` | 設計 token（`web/src/main.tsx` 已載入） | designer |
| `Makefile`、`.env.example`、`.gitattributes`、`.gitignore`、`.github/workflows/ci.yml`、`docker-compose.yml`、`package.json`、`package-lock.json`、`api/pom.xml`、`api/mvnw*`、`api/.mvn/`、`api/src/main/resources/application.yml`、`api/src/test/resources/`、`web/package.json`、`web/{vite.config.ts,tsconfig.json,eslint.config.js}`、`docs/design/`、`docs/contracts/` | 跨界檔案與設計；加相依須經 architect 並列待決定 | architect |

## 設定（寫進 .env.example，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `API_PORT`、`WEB_PORT`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`／`POSTGRES_PASSWORD` | 後端與前端 dev server port（前端把 `/api` 轉給後端）；本機資料庫 | 僅密碼是 |

## 計劃書審視
| 結論 | 項目 | 理由／做法 |
|---|---|---|
| 沿用 | 定義／執行分離與版本綁定、節點純函式 + `requirements()`、Effect 由節點回傳、ArchUnit、Clock 注入、`decisionReason` 強制、outbox + 冪等鍵、三種非同步分類、JDBC 不用 JPA、logic-less 模板、spec 07 的 DDL 決策 | 已落成型別與約束，成本低；`../CDP/backend` 的 V2 migration、兩份 JSON Schema、三個場景 fixture 可搬（改 Boot 4／Testcontainers 2 套件名），V1 依 R2 重寫 |
| 修正 R1 | 計時器兩套：db-scheduler 與 `next_wake_at` + `lockDue … SKIP LOCKED` 重疊 | 只留後者，`Timer` = 在 `StateStore.save` 同一交易寫 `next_wake_at`。「不是輪詢全表」正解是「partial index 掃到期列」 |
| 修正 R2 | RLS 會靜默失效 | superuser 與表擁有者（Flyway 同帳號）略過 RLS；連線池中非交易內設的 `app.tenant_id` 會跨請求殘留。改：owner／app／engine 三 role（engine 獨立連線池）、`FORCE ROW LEVEL SECURITY`、每交易 `set_config(…, true)`，驗收含「app role 讀不到他租戶」 |
| 修正 R3 | 以「多一套基礎設施是負擔」否決 Temporal，卻引入 RabbitMQ、Redis、db-scheduler、MinIO、Vault；RabbitMQ 仍要先寫 outbox | v0.1–v0.2 只用 Postgres，outbox 表即工作佇列（SKIP LOCKED、重試欄位）；RabbitMQ 於 v0.3.0 重評（D-006） |
| 修正 R4 | Phase 1a「暫不處理冪等」違反不變量 #1，且 spec 04 `save()` 的交易形狀事後要改 | outbox 與冪等移入 v0.1.0（Mock 發送下成本極低） |
| 修正 R5 | 1b 的 ConditionNode 要讀 Profile，但 Profile 儲存排在 Phase 3（spec 07 §1） | v0.2.0 明列最小 `ProfileStore`（屬性 JSONB 表 + 寫入 API）與人工維護欄位清單 |
| 修正 R6 | 計劃書 7.4「事件表分區」與 spec 07 §6「不得分區」矛盾；Boot 3.x 已過 OSS 支援 [S1] | 依 spec，分區與 7 天去重同一次上線（v0.4.0）；Boot 改 4.1.x，Java 見 D-007 |
| 修正 R7 | 畫布到 Phase 4 才有，前三期無 UI | v0.1.0 加唯讀 Enrollment 軌跡查詢頁（不變量 #6），旅程先以 JSON API 建立（D-005） |
| 延後 | RabbitMQ、Redis（v0.3.0）、MinIO、Vault/KMS、Kafka、Temporal（換路條件照計劃書 7.3）、OpenTelemetry 全套（先 actuator + 結構化日誌）、React Flow、TanStack Query／Zustand、分析型儲存 | 用到的那一期由設計單列相依待決定 |

## 分期與版號（每階段 = vX.Y.0，修補 = vX.Y.Z）
| 版號 | 計劃書 | 範圍 | 驗收 |
|---|---|---|---|
| v0.1.0 | 1a（縮） | 事件攝取 API（單租戶，欄位帶 `tenant_id`）→ Start／EventSource／WaitDelay／SendMessage(Mock)／End；StateStore、計時器（R1）、outbox + 冪等（R4）、Clock、節點軌跡；軌跡查詢頁（D-005） | 灌事件 → 可控時鐘快轉 → Mock 訊息 → `COMPLETED`；「狀態已提交、Effect 未執行」時中斷重啟，Mock 訊息恰好一則 |
| v0.2.0 | 1b | WaitForEvent／Condition／SplitTest／Exit、G1、G3、版本綁定、RLS（R2）、最小 ProfileStore（R5） | 附錄 C 場景一；同 `cart_id` 觸發 5 次只建 1 個 Enrollment |
| v0.3.0–v0.5.0 | 2／3／4 | 依計劃書第六節，各期開工前出設計單 | 同計劃書各期 |

## 派工順序
1. 甲方回覆 D-004～D-009 → 2. architect 設計 v0.1.0（契約、骨架、migration 自 `../CDP` 搬入）→ 3. backend 與 frontend（對契約 mock）平行，qa 同時寫驗收 → 4. 整合單

## 風險
| 風險 | 對策 |
|---|---|
| Boot 4.1 OSS 支援至 2027-07-31、Temurin 17 至 2027-10-31 [S1][S6] | 2027-06 前排升級單（Boot 下一線、D-007） |
| 本機 Docker daemon 未啟動，Testcontainers 未實測；worktree 跑 `npm ci` 會重建共用 `node_modules` | v0.1.0 開工前實測（需調查 2，#5 繞法備用）；改相依只在單一任務內做，合併後主目錄重跑 `make setup` |

## 來源
[S1] https://endoflife.date/spring-boot [S2] https://docs.spring.io/spring-boot/system-requirements.html [S3] https://github.com/spring-projects/spring-boot/issues/48104 、https://github.com/testcontainers/testcontainers-java/releases [S4] https://java.testcontainers.org/ （未提 api.version）、https://forums.docker.com/t/testcontainer-stopped-working-after-updating-docker-desktop-to-v4-56-0/150823/2 [S5] https://typescript-eslint.io/users/dependency-versions/ 、https://registry.npmjs.org/-/package/typescript/dist-tags 、https://registry.npmjs.org/vitest/latest 、https://registry.npmjs.org/eslint/latest [S6] https://endoflife.date/eclipse-temurin 、https://endoflife.date/maven [S7] https://www.postgresql.org/support/versioning/

## 待甲方決定／需要調查（編號為提案，manager 可重編）
### D-004 本輪範圍是否為 v0.1.0
- 背景：甲方未設本輪範圍；分期表 v0.1.0 為計劃書 1a 縮減版，可獨立驗收
- 選項：A. 照「分期與版號」v0.1.0 ／ B. 再縮（去掉軌跡頁或 outbox）
- 建議：A，R4 指出冪等延後會改交易形狀
- 提出：architect（INIT-01）
### D-005 v0.1.0 是否含唯讀 Enrollment 軌跡查詢頁
- 背景：計劃書畫布到 Phase 4 才有，前三期無 UI（R7）
- 選項：A. 含唯讀軌跡頁 ／ B. 只做 API
- 建議：A，即不變量 #6 的客服畫面，且前端可與後端平行
- 提出：architect（INIT-01）
### D-006 背景任務（C 類）用什麼佇列
- 背景：計劃書引入 RabbitMQ，但仍需先寫 outbox（R3）
- 選項：A. v0.1–v0.2 只用 Postgres outbox 當佇列，v0.3.0 重評 ／ B. v0.3.0 照計劃書上 RabbitMQ ／ C. JobRunr
- 建議：A，少一套基礎設施且 outbox 本來就要做
- 提出：architect（INIT-01）
### D-007 Java 版本與升級時程
- 背景：本機只有 Temurin 17，支援至 2027-10-31；Temurin 25 LTS 支援至 2031-09-30 [S6]；Boot 4.1.1 支援 Java 17–26 [S2]
- 選項：A. 維持 17，2027-06 前（Boot 4.1 OSS 2027-07-31 結束前）與 Boot 升級同一張單升到 25 ／ B. 現在就裝 Temurin 25 並升級 ／ C. 維持 17 不定時程
- 建議：A，v0.1.0 不需 25 的功能，升級併入既有的 Boot 升級時點
- 提出：architect（INIT-01）
### D-008 計劃書與 spec 是否複製進本 repo
- 背景：CI、worktree、reviewer 看不到 `../CDP`；但 `../CDP` README 註明規格不進版控，本 repo 有 GitHub remote
- 選項：A. spec 複製到 `docs/spec/` 由本 repo 維護、計劃書只引用 ／ B. 兩者複製為唯讀參考 ／ C. 不複製
- 建議：A，設計單要能引用 spec 章節
- 提出：architect（INIT-01）
### D-009 前後端相依套件核可
- 背景：後端已放 spring-boot-starter-webmvc、spring-boot-starter-test、spotless-maven-plugin；v0.1.0 將加 spring-boot-starter-jdbc、postgresql、flyway、testcontainers-postgresql 2.0.5、archunit、json-schema-validator。前端已放 react、react-dom、vite、@vitejs/plugin-react、typescript、vitest、eslint、@eslint/js、typescript-eslint、eslint-plugin-react-hooks、globals、@types/react、@types/react-dom、@types/node；UI 測試時加 @testing-library/react、jsdom
- 選項：A. 全部核可 ／ B. 逐項核可
- 建議：A，每項用途見回報 INIT-01
- 提出：architect（INIT-01）
- 需調查：2 Testcontainers 2.0.5 在本機 Docker 29（Windows Docker Desktop）是否需 #5 繞法——查無官方說明 [S4]，待 daemon 啟動實測；3 npm 經 worktree 的 `node_modules` symlink 寫入是否正常
