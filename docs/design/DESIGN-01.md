# 設計：v0.1.0 旅程引擎直線流程（DESIGN-01）
- 契約：`docs/contracts/journey-engine.md`（下稱「契約」）；範圍 D-004～D-006；技術棧與 R1～R7 見 `docs/design/INIT-01.md`；表語意見 spec 07 §10
- 流程：`EventController` → `EventIngestionService`（驗證、`event` 去重落地）→ `EventDispatcher`（輪詢未派發事件、比對 ACTIVE 旅程的 `EventSourceNode`）→ `JourneyRuntime.start` → `EnrollmentAdvancer`（execute → `StateStore.save` 單一交易寫狀態＋軌跡＋`effect_outbox`＋`next_wake_at`）→ `AdvanceLoop`（`lockDue` 到期 → `TimerFired`）→ `OutboxRelay`（confirm 後標 PUBLISHED）→ RabbitMQ `cdp.effects.send-message` → `DeliveryConsumer`（冪等）→ `ChannelEffectExecutor` → `MockChannelAdapter`；查詢：`SecurityConfig`（`ApiKeyAuthenticationFilter`／session）→ `EnrollmentQueryController` → `JdbcEnrollmentQueryStore` → `PiiMasker`

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 套件：`engine`（抽象、`model`、`node`、`node.builtin`、`runtime`）、`journey`、`ingest`、`delivery`（`relay`、`channel`）、`persistence`（全部 JDBC）、`query`、`common`；規則在 `ArchitectureTest`（spec 04 §9 六條＋只有 `delivery.relay` 可依賴 `RabbitTemplate`＋全系統禁 `Instant.now()`） | 用型別與 ArchUnit 守換路與紅線 2 |
| 2 | Migration：V1 = `../CDP` 的 V2 原樣搬入；V2 = `dispatched_at`、查詢索引、outbox、`delivery_task`、`mock_channel_message`；V3 = `event` 派發重試欄位（spec 07 §11，D-024）；V4 = 回填＋`dispatch_outcome` 雙向 CHECK（同 §11「過渡」）；已套用的 migration 不改（含註解，checksum），反向步驟每支 migration 各寫在自己檔頭（V3 例外，寫在 V4）；role 與 RLS 留 v0.2.0（R2） | 已實測 PG 18 上 V1→V4 成功、`lockDue` 走 `ix_enrollment_wake` |
| 3 | 事件扇出用 `event.dispatched_at` 輪詢（同交易 lock → start → 標記），攝取 API 不同步建立 Enrollment；失敗退避重試見 spec 07 §11 | A 類維持 Postgres；crash 不漏不重 |
| 4 | relay：body = `idempotency_key`，publisher confirm（simple；FIX-03 起 correlated＋mandatory）後 `markPublished`；consumer：`insertIfAbsentAndLock` → 已 SENT 即 ack → KillSwitch（`journey.status`）→ Mock 送出＋`delivery_task`＋`delivery_history` 追加同一交易；例外分流見下表 | 紅線 1～3；兩處中斷皆恰好一則 |
| 5 | 測試接縫：可調 `Clock` 以 `@Primary` bean 覆蓋 `ClockConfig`；`AdvanceLoop`／`EventDispatcher`／`OutboxRelay` 的 `runOnce()` 可直接呼叫；`FaultInjector` 三個 `FaultPoint` 模擬中斷；`@ActiveProfiles("test")` 讀 `application-test.yml`，連線由 Testcontainers `@ServiceConnection` 提供 | 驗收「快轉」與「中斷重啟」可重現，不依賴 `.env` |
| 6 | `SendMessageNode` 回 Effect 並走 `sent`（= 意圖已交管線）；v0.1.0 無守門，不走 `skipped`；結果看 `delivery_task` | 節點是純函式，看不到管線結果 |
| 7 | spec 修訂：README 計劃書路徑、`next_wake_at` 改依 R1、spec 05 入口改 outbox → relay → RabbitMQ（INIT-01 #7 三處）；另補 `ResultStatus.COMPLETE`、`start(sourceNodeId)`、`findOpenTrace`、`DeliveryRecord.traceSeq`、`node_trace` 等待列「補完一次」、`delivery` 表改名 `delivery_task`、Flyway 台帳 | 直線流程實作時會撞到的缺口 |
| 8 | 相依（版本由 Boot 4.1.1 BOM 管）：`spring-boot-starter-{validation,jdbc,flyway,amqp}`、`flyway-database-postgresql`、`postgresql`；測試 `spring-boot-starter-{webmvc,amqp}-test`、`spring-boot-testcontainers`、`testcontainers-{junit-jupiter,postgresql,rabbitmq}`、`awaitility`、`archunit-junit5` 1.5.1 | D-009；JSON Schema 驗證器延後（需調查 1） |
| 9 | v0.1.0 不做：`settings.goal`／`global_exit` 評估、G3 去重、身分解析（`profile_id` 必填）、cancel（G10）、登入限速 | D-004 範圍；介面已留 |
| 10 | 認證（契約 §1.1、§1.2，D-013）：`spring-boot-starter-security`；攝取／管理用 Bearer 靜態金鑰（D-019，暫不照 spec 01 §4 分來源）；客服用一組 .env 帳密登入、記憶體 session cookie（D-020）；session 只准 GET，故關 CSRF；`RequestIdFilter` 排在 security 之前，401／403 也帶 `request_id`；遮罩（契約 §4.4，D-014）：後端在 `EnrollmentQueryService.detail` 回傳前呼叫 `PiiMasker.mask`；白名單 `CDP_TRACE_PLAIN_KEYS`，未列即遮（D-021）；`decision_reason` 不得引用屬性值 | v0.1.0 無欄位敏感等級（spec 06 §1 的 `Sensitivity` 尚未實作），只能預設全遮；認證最少相依、不需外部 IdP |
| 11 | 錯誤：唯一 `@RestControllerAdvice` = `common.web.ApiExceptionHandler`；業務錯誤一律 `throw new ApiException(ErrorCode, …)`，不新增 advice、不用 `ResponseStatusException` 或自組 body；filter 層 401／403 由 `security.SecurityProblemWriter` 輸出同格式；新 code 先改契約 §5 再加 `ErrorCode` | 格式只有一個出口；Boot 內建 handler 曾先攔截而輸出錯格式（FIX-01） |
| 12 | 交易鏈：一次 `advance` 的連續推進（≤ `MAX_STEPS`）共用一個交易：呼叫端 `AdvanceLoop.runOnce`／`EventDispatcher.runOnce` 開交易並鎖列，`JourneyRuntime.start`／`EnrollmentAdvancer.advance` 為 NESTED savepoint，鏈上每步 `StateStore.save` 都在其中；任一步失敗整條鏈（狀態、軌跡、outbox、`next_wake_at`）回滾到推進前，只影響該筆 | 守住「狀態＋軌跡＋outbox 同生同滅」；BE-01 實作，review 接受 |

### 做法 #4：consumer 例外分流（BE-02 追加 1）
| 情況 | 處置 |
|---|---|
| `TransientDataAccessException`、`RecoverableDataAccessException`、`DataAccessResourceFailureException`（含 `CannotGetJdbcConnectionException`）、`CannotCreateTransactionException` | `ImmediateRequeueAmqpException`：重排、不進 DLQ（FIX-03 起先等 `DELIVERY_REQUEUE_DELAY`） |
| 其他 `DataAccessException`、payload 解析失敗、未列的 `effect_type`（spec 07 §10） | `AmqpRejectAndDontRequeueException` → DLQ |
| KillSwitch（`journey.status` 非 ACTIVE） | `delivery_task` 寫 `CANCELLED` 後 ack；不寫 `mock_channel_message` 與 `delivery_history` |

## 設定（寫進 .env.example 的單負責，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `CDP_TENANT_ID`；`POSTGRES_HOST`、`RABBITMQ_HOST`；`ENGINE_POLL_INTERVAL`、`ENGINE_BATCH_SIZE`、`OUTBOX_RELAY_POLL_INTERVAL`、`OUTBOX_RELAY_BATCH_SIZE` | 單租戶 tenant_id（ULID）；後端連線主機；推進迴圈＋事件派發、relay 的輪詢間隔（ISO Duration）與每批筆數 | 否 |
| `CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`、`CDP_SUPPORT_PASSWORD`（`CHANGE_ME`，隨機產生）；`CDP_SUPPORT_USERNAME`、`CDP_SESSION_IDLE_TIMEOUT`（ISO Duration）；`CDP_TRACE_PLAIN_KEYS`（逗號分隔；`-` = 全遮，契約 §4.4） | 契約 §1.1、§1.2、§4.4 | 前三個是 |
| FIX-03：`ENGINE_DISPATCH_MAX_ATTEMPTS`（次數）、`ENGINE_DISPATCH_BACKOFF_INITIAL`、`ENGINE_DISPATCH_BACKOFF_MAX`、`OUTBOX_RELAY_RETRY_DELAY`、`DELIVERY_REQUEUE_DELAY`（後四個 ISO Duration） | 派發重試上限與指數退避（spec 07 §11）；relay publish 失敗後該列延後；consumer 暫時性 DB 例外重排前等待 | 否 |

## 檔案分工（`J` = `api/src/main/java/com/cdp`，`T` = `api/src/test/java/com/cdp`）
- 首批 BE-01～04、FE-01、QA-01、INT-01 的分工見 PR #7（皆已合併）。常態：backend `J/`、`T/`（`T/acceptance/`、`T/architecture/` 除外，範圍依派工單）；frontend `web/src/`（`styles/tokens.css` 除外）、`web/tests/unit/`；designer `web/src/styles/tokens.css`、`docs/ui/`；qa `T/acceptance/`、`web/tests/acceptance/`；architect `api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、`T/architecture/`、`docs/{design,contracts,spec}/`（可隨單授權；其餘跨界檔見 INIT-01）

## 後續實作項（DESIGN-02 彙整）
| 項目（來源） | 建議單 |
|---|---|
| 錯誤處理（PR #16、布告欄）：契約 §5 的 `405`／`406` code 與 `Allow`／`Accept` 標頭（沿用 `ErrorResponse.getHeaders()`）；關 `spring.mvc.problemdetails.enabled` 以移除 `@Order` 依賴；`AccessDeniedException`／`AuthenticationException` 交回 Security，不轉 500；`common/web` 單元測試；datasource `logServerErrorDetail=false`；`ArchitectureTest` 限唯一 advice、禁 `ResponseStatusException` | FIX-02 backend（`application.yml`、`T/architecture/` 隨單授權）；qa 更新 `FrameworkErrorFormatAcceptanceTest` 的 code 清單 |
| 認證強化（PR #14）：授權改預設拒絕＋白名單、FORWARD permitAll 收斂；`AuthProperties`／`LoginRequest` 覆寫 `toString` 遮機密；logout cookie 名稱與路徑讀 `ServerProperties`；`AuthProperties` 啟動驗證（空值、兩把金鑰相同即拒啟動） | FIX-04 backend；閒置逾時驗收測試歸 qa |
| 登入限速；請求大小上限（契約 §5 `413`，D-023） | v0.2.0 以後 |

## FIX-03 引擎與發送穩健（PR #13、PR #24 review；D-024、D-025；先 qa 寫驗收，再 backend）
| 項目 | 做法 | 驗收要點 |
|---|---|---|
| 派發重試 | `EventDispatcher` 依 spec 07 §11；`EventStore.lockUndispatched` 加 `now`、`markDispatched` 加 `outcome`、新增 `recordFailure(tenantId, eventId, error)→int`（回傳新次數）＋`scheduleRetry(tenantId, eventId, nextDispatchAt)`；新 `ingest.DispatchProperties`（`cdp.engine.dispatch.*`） | 綁定持續失敗：未達上限前 `next_dispatch_at` 依退避遞增，期間後續事件照常派發；達上限 → `EXHAUSTED`、無 Enrollment；中途恢復 → `DISPATCHED`、不重建 |
| 推進缺資料 | `DefaultEnrollmentAdvancer.advance` 找不到定義或 open trace → `FAILED`＋軌跡，不丟例外 | 喚醒時定義已不存在 → `FAILED`，軌跡有 `decision_reason` |
| 輸入與對照 | `EventValidator` 拒絕含 U+0000 的字串（jsonb 不收）；`JdbcStateStore` 的 `effect_type` 改明確對照（spec 07 §10），不用 `getSimpleName` | U+0000 → `400` `VALIDATION_FAILED`，`errors` 指出欄位；對照有單元測試 |
| relay | 首次 publish 失敗即結束批次，該列 `reschedule(now + OUTBOX_RELAY_RETRY_DELAY)`；`publisher-confirm-type: correlated`＋`publisher-returns: true`＋`template.mandatory: true`，`CorrelationData.getReturned()` 非空視同失敗；`RelayProperties` 加 `retryDelay` | broker 不可用時一批只等一次 confirm 逾時；無 binding 時留 `PENDING`、不標 `PUBLISHED` |
| consumer | 暫時性 DB 例外（上表第一列）重排前等 `DELIVERY_REQUEUE_DELAY`；`channel_type` 取 adapter，不寫死 `"MOCK"`；`T/delivery/` 補單元測試 | DB 斷線期間不空轉重投；恢復後恰好一則；qa 補 `CannotCreateTransactionException` 重排驗收 |
| `missingQueuesFatal` | qa 先讓 `EngineTestSupport`、`SecurityTestSupport` 不再用空 `effectTopology`（改真拓樸或 listener `auto-startup=false`）；backend 再刪 `RabbitTopology.listenerContainerCustomizer` | 全部驗收測試通過 |

- backend 負責檔案：`J/{ingest,delivery}/`、`J/engine/runtime/`、`J/persistence/Jdbc{EventStore,StateStore}.java`、`T/{ingest,engine,delivery,persistence}/`、`application.yml` 與 `application-test.yml`（限上表的 `cdp.*` 與 `spring.rabbitmq` 三鍵）、`.env.example`（上表「設定」FIX-03 列）；qa 負責檔案：`T/acceptance/{engine,ingest,delivery}/`、`T/acceptance/security/SecurityTestSupport.java`

## 待甲方決定／需要調查／風險
- 待決定：D-019～D-025 已定案；FIX-03 新變數的值見 CHORE-02 回報
- 需調查：1 Jackson 3（Boot 4）可用的 JSON Schema 驗證器與版本，決定 v0.2.0 起是否以 `event.schema.json`／`journey-definition.schema.json` 驗證（INIT-01 需調查 2 已結案：Testcontainers 2.0.5 免繞法）
- 風險：cookie 固定 `Secure`，非 localhost 的 HTTP 部署無法登入 → 部署走 HTTPS；登入無限速 → v0.2.0 以後（見「後續實作項」）
