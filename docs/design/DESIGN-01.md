# 設計：v0.1.0 旅程引擎直線流程（DESIGN-01）
- 契約：`docs/contracts/journey-engine.md`（下稱「契約」）；範圍 D-004～D-006；技術棧與 R1～R7 見 `docs/design/INIT-01.md`；表語意見 spec 07 §10
- 流程：`EventController` → `EventIngestionService`（驗證、`event` 去重落地）→ `EventDispatcher`（輪詢未派發事件、比對 ACTIVE 旅程的 `EventSourceNode`）→ `JourneyRuntime.start` → `EnrollmentAdvancer`（execute → `StateStore.save` 單一交易寫狀態＋軌跡＋`effect_outbox`＋`next_wake_at`）→ `AdvanceLoop`（`lockDue` 到期 → `TimerFired`）→ `OutboxRelay`（confirm 後標 PUBLISHED）→ RabbitMQ `cdp.effects.send-message` → `DeliveryConsumer`（冪等）→ `ChannelEffectExecutor` → `MockChannelAdapter`；查詢：`SecurityConfig`（`ApiKeyAuthenticationFilter`／session）→ `EnrollmentQueryController` → `JdbcEnrollmentQueryStore` → `PiiMasker`

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 套件：`engine`（抽象、`model`、`node`、`node.builtin`、`runtime`）、`journey`、`ingest`、`delivery`（`relay`、`channel`）、`persistence`（全部 JDBC）、`query`、`common`；規則在 `ArchitectureTest`（spec 04 §9 六條＋只有 `delivery.relay` 可依賴 `RabbitTemplate`＋全系統禁 `Instant.now()`） | 用型別與 ArchUnit 守換路與紅線 2 |
| 2 | Migration：V1 = `../CDP` 的 V2 原樣搬入；V2 = `dispatched_at`、查詢索引、outbox、`delivery_task`、`mock_channel_message`；role 與 RLS 留 v0.2.0（R2） | 已實測 PG 18 上 V1→V2 成功、`lockDue` 走 `ix_enrollment_wake` |
| 3 | 事件扇出用 `event.dispatched_at` 輪詢（同交易 lock → start → 標記），攝取 API 不同步建立 Enrollment | A 類維持 Postgres；crash 不漏不重 |
| 4 | relay：body = `idempotency_key`，publisher confirm（simple）後 `markPublished`；consumer：`insertIfAbsentAndLock` → 已 SENT 即 ack → KillSwitch（`journey.status`）→ Mock 送出＋`delivery_task`＋`delivery_history` 追加同一交易；解析失敗進 DLQ，DB 不可用丟 `ImmediateRequeueAmqpException` | 紅線 1～3；兩處中斷皆恰好一則 |
| 5 | 測試接縫：可調 `Clock` 以 `@Primary` bean 覆蓋 `ClockConfig`；`AdvanceLoop`／`EventDispatcher`／`OutboxRelay` 的 `runOnce()` 可直接呼叫；`FaultInjector` 三個 `FaultPoint` 模擬中斷；`@ActiveProfiles("test")` 讀 `application-test.yml`，連線由 Testcontainers `@ServiceConnection` 提供 | 驗收「快轉」與「中斷重啟」可重現，不依賴 `.env` |
| 6 | `SendMessageNode` 回 Effect 並走 `sent`（= 意圖已交管線）；v0.1.0 無守門，不走 `skipped`；結果看 `delivery_task` | 節點是純函式，看不到管線結果 |
| 7 | spec 修訂：README 計劃書路徑、`next_wake_at` 改依 R1、spec 05 入口改 outbox → relay → RabbitMQ（INIT-01 #7 三處）；另補 `ResultStatus.COMPLETE`、`start(sourceNodeId)`、`findOpenTrace`、`DeliveryRecord.traceSeq`、`node_trace` 等待列「補完一次」、`delivery` 表改名 `delivery_task`、Flyway 台帳 | 直線流程實作時會撞到的缺口 |
| 8 | 相依（版本由 Boot 4.1.1 BOM 管）：`spring-boot-starter-{validation,jdbc,flyway,amqp}`、`flyway-database-postgresql`、`postgresql`；測試 `spring-boot-starter-{webmvc,amqp}-test`、`spring-boot-testcontainers`、`testcontainers-{junit-jupiter,postgresql,rabbitmq}`、`awaitility`、`archunit-junit5` 1.5.1 | D-009；JSON Schema 驗證器延後（需調查 1） |
| 9 | v0.1.0 不做：`settings.goal`／`global_exit` 評估、G3 去重、身分解析（`profile_id` 必填）、cancel（G10）、登入限速 | D-004 範圍；介面已留 |
| 10 | 認證（契約 §1.1、§1.2，D-013）：`spring-boot-starter-security`；攝取／管理用 Bearer 靜態金鑰（暫不照 spec 01 §4 分來源，待決定 1）；客服用一組 .env 帳密登入、記憶體 session cookie（待決定 2）；session 只准 GET，故關 CSRF；`RequestIdFilter` 排在 security 之前，401／403 也帶 `request_id`；遮罩（契約 §4.4，D-014）：後端在 `EnrollmentQueryService.detail` 回傳前呼叫 `PiiMasker.mask`；白名單 `CDP_TRACE_PLAIN_KEYS`，未列即遮（待決定 3）；`decision_reason` 不得引用屬性值 | v0.1.0 無欄位敏感等級（spec 06 §1 的 `Sensitivity` 尚未實作），只能預設全遮；認證最少相依、不需外部 IdP |

## 設定（BE-01 寫進 .env.example，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `CDP_TENANT_ID`；`POSTGRES_HOST`、`RABBITMQ_HOST`；`ENGINE_POLL_INTERVAL`、`ENGINE_BATCH_SIZE`、`OUTBOX_RELAY_POLL_INTERVAL`、`OUTBOX_RELAY_BATCH_SIZE` | 單租戶 tenant_id（ULID）；後端連線主機；推進迴圈＋事件派發、relay 的輪詢間隔（ISO Duration）與每批筆數 | 否 |
| `CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`、`CDP_SUPPORT_PASSWORD`（`CHANGE_ME`，隨機產生）；`CDP_SUPPORT_USERNAME`、`CDP_SESSION_IDLE_TIMEOUT`（ISO Duration）；`CDP_TRACE_PLAIN_KEYS`（逗號分隔，空 = 全遮） | 契約 §1.1、§1.2、§4.4 | 前三個是 |

## 檔案分工（`J` = `api/src/main/java/com/cdp`，`T` = `api/src/test/java/com/cdp`）
| 檔案 | 負責 |
|---|---|
| `J/CdpApplication.java`、`J/{common,engine,journey,ingest}/`、`J/persistence/Jdbc{StateStore,Timer,JourneyDefinitionStore,EventStore,OutboxStore}.java`、`T/{common,engine,journey,ingest,persistence}/`、`.env.example` | BE-01 backend |
| `J/delivery/`（含 `relay/`、`channel/`）、`J/persistence/JdbcDeliveryTaskStore.java`、`T/delivery/` | BE-02 backend |
| `J/query/`（含 `PiiMasker`）、`J/persistence/JdbcEnrollmentQueryStore.java`、`T/query/` | BE-03 backend |
| `J/security/`、`T/security/` | BE-04 backend |
| `web/src/`（`styles/tokens.css` 除外）、`web/tests/unit/` | FE-01 frontend |
| `web/src/styles/tokens.css`、`docs/ui/` | designer |
| `T/acceptance/`、`web/tests/acceptance/` | QA-01 qa |
| `api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、`T/architecture/`、`docs/{design,contracts,spec}/`；其餘跨界檔見 INIT-01 | architect |

## 派工順序
1. 平行：BE-01（引擎、攝取、旅程 API）、BE-03（查詢 API＋遮罩，測試自行灌表）、FE-01（依契約 §1.2、§4 mock）、QA-01（兩個 acceptance 目錄）、designer 修訂 UI-01（下節）
2. BE-02（relay、consumer、Mock）：相依 BE-01 的 `JdbcOutboxStore`、`JdbcJourneyDefinitionStore`；BE-04（認證）相依待決定 1、2 定案（契約 §1.1、§1.2 依各推薦選項寫，選別的再改契約）
3. INT-01（frontend）：FE-01 改接真實 API（含登入），`make infra-up` + `make api-run` + `make web-dev` 走完整流程。BE-04 合併前沿用 Boot 預設安全設定，BE-01～03 的 web 測試用 `@WithMockUser`。驗收測試目錄（皆 QA-01 寫，憑證用 `application-test.yml`）：BE-01～04 → `T/acceptance/`；FE-01、INT-01 → `web/tests/acceptance/`

## UI-01 對不上的地方（轉 designer）
| 設計稿 | 契約 | 要改 |
|---|---|---|
| 軌跡第一列為「開始 Start」、出口 `default`、型別 `WaitDelay` | 第一列是 `EventSourceNode`；出口 `next`／`sent`；`node_type` 為完整名 | 依契約 §4.1 `TraceEntry` |
| 發送歷史有模板名稱、LINE、行銷；「完整紀錄請至發送紀錄查詢」 | v0.1.0 只有 `template_id`、`channel_id`、`channel_type=MOCK`、`message_type=null`；更舊紀錄用 `/deliveries` 續查 | 改顯示 ID 與「—」；改為本頁「載入更早紀錄」 |
| 「已略過（靜音時段）」範例、客戶屬性快照 name／tier、來源 web | v0.1.0 無守門、`profile_snapshot` 為 `{}`、來源是 `source_type` | 範例改 v0.1.0 會出現的情況；快照區補空狀態 |
| 狀態篩選「全部／等待中／已完成／失敗」；結果只有「已略過」 | `status` 為 `EnrollmentStatus` 逗號清單；v0.1.0 會出現 `ACTIVE` `WAITING` `COMPLETED` `FAILED`；發送結果另有 `PENDING`（已排入未送，Enrollment 可能已 COMPLETED）、`CANCELLED` | 補「進行中」或併入；終態分組自訂；結果補兩種狀態與摘要句 |
| 「資料更新於」、請求代碼、「查無」 | `as_of`（後端時間，快轉時為模擬時間）、`request_id`；列表查無 = 200 空陣列，404 只在詳情 | 依契約 §1、§5 |
| 無登入畫面 | 契約 §1.2：帳號＋密碼；錯誤只有一種（`INVALID_CREDENTIALS`）；任何 `401` 導回登入後回原頁；`/me` 有 `username` | 補登入頁、登入失敗與 session 逾時的狀態、頁首帳號與登出 |
| 屬性與快照全明文 | 契約 §4.4：遮罩值為字串（`a***@example.com`、`***`），以 `masked_paths` 標示；不能解除遮罩 | 補「已遮罩」呈現與說明；`decision_reason` 不會含個資 |

## 待甲方決定／需要調查
- 待決定（內容見回報）：1 攝取 API 認證方式；2 客服帳號來源；3 遮罩名單策略
- 需調查：1 Jackson 3（Boot 4）可用的 JSON Schema 驗證器與版本，決定 v0.2.0 起是否以 `event.schema.json`／`journey-definition.schema.json` 驗證（INIT-01 需調查 2 已結案：Testcontainers 2.0.5 免繞法）

## 風險
| 風險 | 對策 |
|---|---|
| BE-01 合併前 `.env.example` 缺「設定」的變數，`make api-run` 缺值；cookie 固定 `Secure`，非 localhost 的 HTTP 部署無法登入；登入無限速 | BE-01 先加變數、manager 請甲方補 `.env`（test profile 不受影響）；部署走 HTTPS；限速列入下一期設計單 |
