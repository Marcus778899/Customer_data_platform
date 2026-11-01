# 設計：專案初始化（INIT-01）

- 契約：無。依據 D-001～D-009（`docs/decisions.md`）；「計劃書」= `../CDP/docs/plan/backbone-plan.md`（只引用）；「spec」= `docs/spec/`（D-008，原樣複製，與本設計的差異見做法 #7）；版本事實見「來源」[S1]–[S9]（查詢日 2026-10-18）；需調查：2 Testcontainers 2.0.5（PostgreSQL、RabbitMQ 模組）在本機 Docker 29 是否需 #4 繞法（查無官方說明 [S4]，待 daemon 啟動實測）、3 npm 經 worktree 的 `node_modules` symlink 寫入是否正常；設定：變數清單與說明只在 `.env.example`（port、PostgreSQL、RabbitMQ；僅 `*_PASSWORD` 為機密）
- 派工順序：1. architect 設計 v0.1.0（契約、骨架、migration 自 `../CDP` 搬入、spec 修訂見 #7）→ 2. backend 與 frontend（對契約 mock）平行，qa 同時寫驗收 → 3. 整合單

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 後端 Java 17（Temurin，D-007）+ Spring Boot 4.1.1；Maven 3.9.16 + wrapper `api/mvnw`，不用 Gradle | 4.1.1 為 4.1 線最新、最低 Java 17 [S1][S2]；3.5 OSS 已於 2026-06-30 結束 [S1]；Maven 4 仍為 rc [S6]；本機只有 JDK 17 與 Maven；../CDP 前次也是 Maven |
| 2 | 前端 React 19 + TypeScript `~6.0.3` + Vite 8 + Vitest 5 + ESLint 10；Node 24 LTS；根 `package.json` 用 npm workspaces（`web`），`node_modules` 在 checkout 根目錄，Maven 相依在 `~/.m2` | npm latest TS 為 7.0.2，但 typescript-eslint 只支援 TS `>=4.8.4 <6.1.0` [S5]，升 7 會讓 lint 失去支援；Vitest／ESLint engines 皆含 Node 24 [S5]；`scripts/task.py` 只 symlink 根層 `node_modules` 進 worktree |
| 3 | 本機 `docker-compose.yml`（`make infra-up`／`infra-down`，只綁 127.0.0.1）：PostgreSQL 18、RabbitMQ 4.3（含管理介面）；整合測試 Testcontainers 2.0.5 用同版映像 | 計劃書要求 PG 16+，18 支援至 2030-11-14 [S7]；RabbitMQ 4.3 為唯一仍受社群支援的線 [S9]；Testcontainers 1.x 不修 Docker 29，只有 2.x 修 [S3] |
| 4 | Docker 29（最低 API 1.44）相容：先不加繞法；若 2.0.5 仍協商失敗，加 `api/src/test/resources/docker-java.properties` 的 `api.version=1.44`（只影響測試 classpath，不用 `System.setProperty`、不要求改 daemon） | 2.0.3 起已改協商 [S3]；`api.version` 是社群繞法、官方文件未提 [S4]；實測見需調查 2 |
| 5 | `lint` = `mvnw spotless:check` + `eslint` + `tsc --noEmit`；`test` = `mvnw verify` + `vitest run`；`ci-setup` = `setup`；Makefile 自動 export `.env.example` 列的變數；recipe 只用 ASCII；`.gitattributes` 讓 Makefile、`*.sh`、`api/mvnw`、原始碼 eol=lf；`.gitignore` 排除 `api/target/`、`web/dist/` | CI 與本機同一套；新變數不必改 Makefile；Make 3.81 以 cp950 轉碼；autocrlf 下 sh／make 遇 CRLF 會壞 |
| 6 | 相依只在用到的那張設計單加進 `api/pom.xml`（PostgreSQL、Flyway、AMQP 皆留給 v0.1.0 設計單，見 D-009） | 骨架不連外部服務，`make check` 不需 Docker |
| 7 | spec 與本設計的已知差異，由 v0.1.0 設計單修訂 `docs/spec/`：README 第 4 行 `../plan/` 改指計劃書路徑；README「`next_wake_at` 供 db-scheduler」改依 R1；spec 05 管線入口改為「outbox → relay → RabbitMQ」 | spec 是前後端契約的上游，不可與設計矛盾 |

## 驗證方式（compose-test-setup）
| 情境 | 指令 | 跑什麼 |
|---|---|---|
| 本機全部後端測試（含 `acceptance/`） | 在主目錄單獨一行：`docker compose --env-file .env -f .worktrees/<id>/compose.yaml run --rm test`（主目錄本身用 `-f compose.yaml`） | `compose.yaml` 的 `test` 服務：`eclipse-temurin:17-jdk`，把唯讀掛載的 `api/`（不含 `target/`）複製進容器後 `mvnw verify`；不讀 `.env` 的值 |
| `make check` | 照舊 | Docker 連得到（或 CI 變數非空）跑全部；連不到或 `CONTAINER_TESTS=skip` 改 `-Punit-only`（`api/pom.xml`，排除 `com/cdp/acceptance/**`、`*IntegrationTest`），並印出上一列指令 |
| CI | `make check`（不變） | GitHub Actions 設 `CI=true`，一律全跑，Docker 壞了就紅燈 |

| 項目 | 做法與取捨 |
|---|---|
| Testcontainers | 沿用（不改測試、`docker pause` 照常）：`test` 掛 `/var/run/docker.sock`，PG／RabbitMQ 是主機引擎上的兄弟容器，經 `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`（Linux 由 `extra_hosts: host-gateway` 補）連對外 port；Ryuk 照常清理 |
| 安全 | socket 等同主機 Docker root 權限，故只掛 `api/`、`.env.example`（皆唯讀）與 Maven 快取 volume，不寫回主機（所以以 root 跑、不需 `HOST_UID`）；Testcontainers 對外 port 與在主機直接跑時相同 |
| 隔離與殘留 | 不設 `name:`，專案名 = worktree 目錄；`run --rm` 刪 `test` 容器，Testcontainers 容器由 Ryuk 收；留下的只有專案網路（`docker compose -f … down` 可刪）與共用快取 volume `cdp-test-m2` |
| 新測試慣例 | 需要容器的測試只放 `acceptance/` 或命名 `*IntegrationTest`，否則 sandbox 的 `make check` 會失敗 |

## RabbitMQ（D-006；守計劃書 7.2.1 三條紅線）
| 項目 | 設計 |
|---|---|
| 角色與流程 | 只承接 C 類背景任務：Effect 執行（v0.1.0 為 SendMessage→Mock）；A 類事件攝取／扇出仍是 Postgres outbox + 輪詢、B 類長等待仍是 `next_wake_at`（R1）。引擎交易內 `StateStore.save` + 寫 outbox 列 → 獨立 relay（`SKIP LOCKED` 取到期列，publisher confirm 後才標已推送）→ exchange → queue（各配一個 DLQ）→ consumer |
| 紅線 1 長等待不用 MQ 延遲 | 不裝 delayed message plugin、業務 queue 不設 TTL；重試退避與 spec 05 的 `RETRY_NEXT_DAY` 一律由 consumer 回寫 outbox `available_at` 後 ack，由 relay 到期再推；DLQ 只收無法解析或超過重投上限的毒訊息 |
| 紅線 2 入列經 outbox | 只有 relay 套件可依賴 `RabbitTemplate`，以 ArchUnit 規則強制（與 spec 04 的 `ArchitectureTest` 同檔） |
| 紅線 3 consumer 冪等 | 以 Effect 冪等鍵寫 `delivery_task`（唯一約束；即計劃書 7.2.1 要求的任務級狀態表，也供軌跡頁回答「為什麼沒收到」）；已 `SENT` 即 ack 丟棄；v0.1.0 的 Mock 發送與狀態更新同一交易。consumer 每階段前呼叫 kill switch 檢查介面（G10、spec 05 §5），v0.1.0 只查 `journey.status`，Redis 於 v0.3.0 接上 |
| 引入時點與相依 | v0.1.0，與 outbox relay 同一張單：v0.1.0 驗收本來就測「中斷後恰好一則」，relay 與 consumer 冪等的形狀一次定案；延後則 relay 要從「直接執行」改寫成「推 MQ」。相依：`spring-boot-starter-amqp`（Spring AMQP 4.1.1，版本由 Boot BOM 管）、`spring-boot-starter-amqp-test`、`org.testcontainers:testcontainers-rabbitmq` 2.0.5 [S8] |

## 目錄結構與檔案分工
| 路徑 | 內容 | 負責 |
|---|---|---|
| `api/src/main/java/com/cdp/<模組>/`、`api/src/test/java/com/cdp/<模組>/` | 後端程式與單元／整合測試；模組由各設計單定 | backend worker |
| `api/src/test/java/com/cdp/acceptance/`、`web/tests/acceptance/` | 驗收測試：類別名 `*AcceptanceTest`／檔名 `*.test.ts(x)`，`make test` 已涵蓋 | qa（worker 不可改） |
| `web/src/`、`web/tests/unit/` | 前端程式與單元測試 | frontend worker |
| `web/src/styles/tokens.css` | 設計 token（`web/src/main.tsx` 已載入） | designer |
| `Makefile`、`.env.example`、`.gitattributes`、`.gitignore`、`.github/workflows/ci.yml`、`docker-compose.yml`、`package.json`、`package-lock.json`、`api/pom.xml`、`api/mvnw*`、`api/.mvn/`、`api/src/main/resources/application.yml`、`api/src/test/resources/`、`web/package.json`、`web/{vite.config.ts,tsconfig.json,eslint.config.js}`、`docs/design/`、`docs/contracts/`、`docs/spec/` | 跨界檔案、設計與 spec；加相依須經 architect | architect |

## 計劃書審視
| 結論 | 項目 | 理由／做法 |
|---|---|---|
| 沿用 | 定義／執行分離與版本綁定、節點純函式 + `requirements()`、Effect 由節點回傳、ArchUnit、Clock 注入、`decisionReason` 強制、outbox + 冪等鍵、三種非同步分類、7.2.1 三條紅線、JDBC 不用 JPA、logic-less 模板、spec 07 的 DDL 決策 | 已落成型別與約束；`../CDP/backend` 的 V2 migration、兩份 JSON Schema、三個場景 fixture 可搬（改 Boot 4／Testcontainers 2 套件名），V1 依 R2 重寫 |
| 修正 R1 | 計時器兩套：db-scheduler 與 `next_wake_at` + `lockDue … SKIP LOCKED` 重疊 | 只留後者，`Timer` = 在 `StateStore.save` 同一交易寫 `next_wake_at`。「不是輪詢全表」正解是「partial index 掃到期列」 |
| 修正 R2 | RLS 會靜默失效 | superuser 與表擁有者（Flyway 同帳號）略過 RLS；連線池中非交易內設的 `app.tenant_id` 會跨請求殘留。改：owner／app／engine 三 role（engine 獨立連線池）、`FORCE ROW LEVEL SECURITY`、每交易 `set_config(…, true)`，驗收含「app role 讀不到他租戶」 |
| 修正 R3 | 以「多一套基礎設施是負擔」否決 Temporal，卻一次引入 RabbitMQ、Redis、db-scheduler、MinIO、Vault | RabbitMQ 依 D-006 於 v0.1.0 引入，只限 C 類（見「RabbitMQ」節）；db-scheduler 拿掉（R1）；延後到用到的那期、由該期設計單列相依：Redis（v0.3.0）、MinIO、Vault/KMS、Kafka、Temporal（換路條件照計劃書 7.3）、OpenTelemetry 全套（先 actuator + 結構化日誌）、React Flow（v0.2.0，D-018）、TanStack Query／Zustand、分析型儲存 |
| 修正 R4 | Phase 1a「暫不處理冪等」違反不變量 #1，且 spec 04 `save()` 的交易形狀事後要改 | outbox 與冪等移入 v0.1.0（Mock 發送下成本極低） |
| 修正 R5 | 1b 的 ConditionNode 要讀 Profile，但 Profile 儲存排在 Phase 3（spec 07 §1） | v0.2.0 明列最小 `ProfileStore`（屬性 JSONB 表 + 寫入 API）與人工維護欄位清單 |
| 修正 R6 | 計劃書 7.4「事件表分區」與 spec 07 §6「不得分區」矛盾；Boot 3.x 已過 OSS 支援 [S1] | 依 spec，分區與 7 天去重同一次上線（v0.4.0）；Boot 改 4.1.x，Java 見 D-007 |
| 修正 R7 | 畫布到 Phase 4 才有，前三期無 UI | v0.1.0 加唯讀 Enrollment 軌跡查詢頁（不變量 #6，D-005），旅程先以 JSON API 建立；畫布提前到 v0.2.0（D-018） |

## 分期與版號（每階段 = vX.Y.0，修補 = vX.Y.Z；本輪 = v0.1.0，D-004）
| 版號 | 計劃書 | 範圍 | 驗收 |
|---|---|---|---|
| v0.1.0 | 1a（縮） | 事件攝取 API（單租戶，欄位帶 `tenant_id`）→ Start／EventSource／WaitDelay／SendMessage(Mock)／End；StateStore、計時器（R1）、outbox → relay → RabbitMQ → 冪等 consumer → Mock（R4、D-006）、Clock、節點軌跡；軌跡查詢頁（D-005） | 灌事件 → 可控時鐘快轉 → Mock 訊息 → `COMPLETED`；「狀態已提交、未推送」與「已推送、consumer 未 ack」兩處中斷重啟，Mock 訊息皆恰好一則 |
| v0.2.0 | 1b | WaitForEvent／Condition／SplitTest／Exit、G1、G3、版本綁定、RLS（R2）、最小 ProfileStore（R5）；旅程編輯畫布（React Flow，自 Phase 4 提前，D-018） | 附錄 C 場景一；同 `cart_id` 觸發 5 次只建 1 個 Enrollment；以畫布拖拉建出場景一的旅程並發佈，產出的定義通過 DESIGN-01 契約 §3.1 驗證 |
| v0.3.0–v0.5.0 | 2／3／4 | 依計劃書第六節（Phase 4 的畫布已移到 v0.2.0），各期開工前出設計單；v0.3.0 接 Redis（kill switch、頻率上限）與真實通道重試 | 同計劃書各期 |

## 風險
| 風險 | 對策 |
|---|---|
| Boot 4.1 OSS 支援至 2027-07-31、Temurin 17 至 2027-10-31 [S1][S6]；RabbitMQ 4.1、4.2 社群支援皆約 9 個月即結束 [S9]；本機 Docker daemon 未啟動，Testcontainers 未實測；worktree 跑 `npm ci` 會重建共用 `node_modules` | 2027-06 前排升級單（Boot 下一線、Java 25 見 D-007），同單檢查 RabbitMQ 線；v0.1.0 開工前實測（需調查 2，#4 繞法備用）；改相依只在單一任務內做，合併後主目錄重跑 `make setup` |

## 來源
[S1] https://endoflife.date/spring-boot [S2] https://docs.spring.io/spring-boot/system-requirements.html [S3] https://github.com/spring-projects/spring-boot/issues/48104 、https://github.com/testcontainers/testcontainers-java/releases [S4] https://java.testcontainers.org/ （未提 api.version）、https://forums.docker.com/t/testcontainer-stopped-working-after-updating-docker-desktop-to-v4-56-0/150823/2 [S5] https://typescript-eslint.io/users/dependency-versions/ 、https://registry.npmjs.org/-/package/typescript/dist-tags 、https://registry.npmjs.org/vitest/latest 、https://registry.npmjs.org/eslint/latest [S6] https://endoflife.date/eclipse-temurin 、https://endoflife.date/maven [S7] https://www.postgresql.org/support/versioning/ [S8] https://repo1.maven.org/maven2/org/testcontainers/testcontainers-rabbitmq/maven-metadata.xml 、https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom [S9] https://endoflife.date/rabbitmq 、https://hub.docker.com/_/rabbitmq
