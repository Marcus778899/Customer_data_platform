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
| 10 | 認證（契約 §1.1、§1.2，D-013）：`spring-boot-starter-security`；攝取／管理用 Bearer 靜態金鑰（D-019，暫不照 spec 01 §4 分來源）；客服用一組 .env 帳密登入、記憶體 session cookie（D-020）；session 只准 GET，故關 CSRF；`RequestIdFilter` 排在 security 之前，401／403 也帶 `request_id`；遮罩（契約 §4.4，D-014）：後端在 `EnrollmentQueryService.detail` 回傳前呼叫 `PiiMasker.mask`；白名單 `CDP_TRACE_PLAIN_KEYS`，未列即遮（D-021）；`decision_reason` 不得引用屬性值 | v0.1.0 無欄位敏感等級（spec 06 §1 的 `Sensitivity` 尚未實作），只能預設全遮；認證最少相依、不需外部 IdP |
| 11 | 錯誤：唯一 `@RestControllerAdvice` = `common.web.ApiExceptionHandler`；業務錯誤一律 `throw new ApiException(ErrorCode, …)`，不新增 advice、不用 `ResponseStatusException` 或自組 body；filter 層 401／403 由 `security.SecurityProblemWriter` 輸出同格式；新 code 先改契約 §5 再加 `ErrorCode` | 格式只有一個出口；Boot 內建 handler 曾先攔截而輸出錯格式（FIX-01） |
| 12 | 交易鏈：一次 `advance` 的連續推進（≤ `MAX_STEPS`）共用一個交易：呼叫端 `AdvanceLoop.runOnce`／`EventDispatcher.runOnce` 開交易並鎖列，`JourneyRuntime.start`／`EnrollmentAdvancer.advance` 為 NESTED savepoint，鏈上每步 `StateStore.save` 都在其中；任一步失敗整條鏈（狀態、軌跡、outbox、`next_wake_at`）回滾到推進前，只影響該筆 | 守住「狀態＋軌跡＋outbox 同生同滅」；BE-01 實作，review 接受 |

## 設定（BE-01 寫進 .env.example，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `CDP_TENANT_ID`；`POSTGRES_HOST`、`RABBITMQ_HOST`；`ENGINE_POLL_INTERVAL`、`ENGINE_BATCH_SIZE`、`OUTBOX_RELAY_POLL_INTERVAL`、`OUTBOX_RELAY_BATCH_SIZE` | 單租戶 tenant_id（ULID）；後端連線主機；推進迴圈＋事件派發、relay 的輪詢間隔（ISO Duration）與每批筆數 | 否 |
| `CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`、`CDP_SUPPORT_PASSWORD`（`CHANGE_ME`，隨機產生）；`CDP_SUPPORT_USERNAME`、`CDP_SESSION_IDLE_TIMEOUT`（ISO Duration）；`CDP_TRACE_PLAIN_KEYS`（逗號分隔；`-` = 全遮，契約 §4.4） | 契約 §1.1、§1.2、§4.4 | 前三個是 |

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
1. 平行：BE-01（引擎、攝取、旅程 API）、BE-03（查詢 API＋遮罩，測試自行灌表）、FE-01（依契約 §1.2、§4 mock）、QA-01（兩個 acceptance 目錄）、designer 修訂 UI-01（PR #8）
2. BE-02（relay、consumer、Mock）：相依 BE-01 的 `JdbcOutboxStore`、`JdbcJourneyDefinitionStore`；BE-04（認證，D-019、D-020）
3. INT-01（frontend）：FE-01 改接真實 API（含登入），`make infra-up` + `make api-run` + `make web-dev` 走完整流程。BE-04 合併前沿用 Boot 預設安全設定，BE-01～03 的 web 測試用 `@WithMockUser`。驗收測試目錄（皆 QA-01 寫，憑證用 `application-test.yml`）：BE-01～04 → `T/acceptance/`；FE-01、INT-01 → `web/tests/acceptance/`

## 後續實作項（DESIGN-02 彙整；UI-01 對不上處已於 PR #8 修訂）
| 項目（來源） | 建議單 |
|---|---|
| 錯誤處理（PR #16、布告欄）：契約 §5 的 `405`／`406` code 與 `Allow`／`Accept` 標頭（沿用 `ErrorResponse.getHeaders()`）；關 `spring.mvc.problemdetails.enabled` 以移除 `@Order` 依賴；`AccessDeniedException`／`AuthenticationException` 交回 Security，不轉 500；`common/web` 單元測試；datasource `logServerErrorDetail=false`；`ArchitectureTest` 限唯一 advice、禁 `ResponseStatusException` | FIX-02 backend（`application.yml`、`T/architecture/` 隨單授權）；qa 更新 `FrameworkErrorFormatAcceptanceTest` 的 code 清單 |
| 引擎穩健（PR #13）：`DefaultEnrollmentAdvancer.advance` 找不到定義或 open trace → 轉 `FAILED`＋軌跡，不丟例外；`EventDispatcher` 重試上限＋退避，失敗事件不得占住 `lockUndispatched` 批首（需 V3 `event` 欄位，待決定 2）；`EventValidator` 拒絕含 `\u0000` 的字串 → `400`（jsonb 不收）；`effect_type` 改明確對照（spec 07 §10） | FIX-03 backend（V3 由 architect 先出） |
| 認證強化（PR #14）：授權改預設拒絕＋白名單、FORWARD permitAll 收斂；`AuthProperties`／`LoginRequest` 覆寫 `toString` 遮機密；logout cookie 名稱與路徑讀 `ServerProperties`；`AuthProperties` 啟動驗證（空值、兩把金鑰相同即拒啟動） | FIX-04 backend；閒置逾時驗收測試歸 qa |
| `CDP_TRACE_PLAIN_KEYS` 的 `-` 解析與 `MaskingProperties` 註解（契約 §4.4） | BE-03（進行中） |
| 登入限速；請求大小上限（契約 §5 `413`，待決定 1） | v0.2.0 設計單，或依待決定提前 |

## 待甲方決定／需要調查
- 待決定：D-019～D-021 已定案；DESIGN-02 新增的見其回報（1 請求大小上限；2 派發重試耗盡的處置；3 後續修正單納入範圍）
- 需調查：1 Jackson 3（Boot 4）可用的 JSON Schema 驗證器與版本，決定 v0.2.0 起是否以 `event.schema.json`／`journey-definition.schema.json` 驗證（INIT-01 需調查 2 已結案：Testcontainers 2.0.5 免繞法）

## 風險
| 風險 | 對策 |
|---|---|
| BE-01 合併前 `.env.example` 缺「設定」的變數，`make api-run` 缺值；cookie 固定 `Secure`，非 localhost 的 HTTP 部署無法登入；登入無限速 | BE-01 先加變數、manager 請甲方補 `.env`（test profile 不受影響）；部署走 HTTPS；限速列入下一期設計單 |
