# 設計：v0.2.0 多租戶、客戶、分支引擎、畫布與 relay 改寫（DESIGN-03）
- 契約：`docs/contracts/tenancy-auth.md`、`profiles.md`、`journeys.md`（新）；`journey-engine.md`（§4 瀏覽、§4.5 圖形軌跡、§5 新錯誤碼）；表與 role 見 spec 07 §12；G3、喚醒見 spec 02 §4、§6「落地」；SplitTest、驗證、global_exit 見 spec 03；停止見 spec 05 §5「落地」；外部 review 見 #72（D-036～D-038）；`J`、`T` 同 DESIGN-01

## 拆版提案（待決定 1；單數 = 「派工順序」表的單號數，qa 單與 a／b 各算一張，不含 UI-03）
| 方案 | 第一版（v0.2.0） | 第二版（v0.3.0） | 單數 |
|---|---|---|---|
| **A（建議）** | relay／迴圈改寫＋壓測、多租戶（RLS、租戶使用者登入、角色、工作區）、ProfileStore＋客戶搜尋＋客戶頁、外框分頁＋受眾佔位、旅程列表（唯讀）、最近 Enrollment 瀏覽（D-030）、圖形軌跡（直線旅程） | 旅程生命週期（草稿、發佈、停止）、畫布（D-018）、分支節點＋goal／global_exit、G1、G3、分支與循環軌跡、節點統計 | 14＋8 |
| B | A 第一版＋生命週期＋畫布（只有 v0.1.0 五種節點） | 分支節點＋G1＋G3＋分支軌跡 | 17＋5 |
| C | 全部一版 | — | 22 |
- 驗收：A 第一版 = 兩租戶的使用者各自登入只看到自己的資料、`cdp_app` 讀不到他租戶（INIT-01 R2）、四種方式搜到客戶並開圖形軌跡、relay 新情境（下表）全綠、壓測報告；第二版 = 場景一縮減版（待決定 6）、同 `cart_id` 5 次只建 1 個、以畫布建出場景一並發佈、v1 等待中發佈 v2 不受影響
- 算法：A 第一版 14 = 批 1～5（BE-05、QA-02、FE-02、QA-03、BE-06、QA-04a、BE-07、QA-04b、FE-03、QA-05a、BE-08、QA-05b、QA-06、INT-02），第二版 8 = 「二」列；B 把 BE-09、QA-07、FE-04 移到第一版。前版「12＋8」把 QA-04a／b、QA-05a／b 各算一張，已更正。A 與 B 都會讓計劃書 Phase 2 起順延一版號（受眾變 v0.5.0，牽動 D-034）；A 另把畫布從 v0.2.0 移到 v0.3.0（牽動 D-018）。建議 A：先把租戶與連線池這種「事後補很痛」的底座做完，新表一開始就有 RLS；畫布等分支節點一起做，設定表單不必做兩次
## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 雙連線池（spec README「RLS 的唯一例外」）：`common.db.DataSourceConfig` 建應用池（`@Primary`，`SET ROLE cdp_app`）與引擎池（`SET ROLE journey_engine`；不帶 `BYPASSRLS`〔PG 18 建立需 superuser〕，改由每張 RLS 表的 `engine_all` policy 放行，spec 07 §2、§12），同一登入帳號 `CDP_DB_APP_USER`；Flyway 用 `POSTGRES_USER`，`afterMigrate` callback 建／更新登入帳號；應用池交易管理器 `TenantAwareTransactionManager`（繼承 `DataSourceTransactionManager`，`prepareTransactionalConnection` 執行 `set_config('app.tenant_id', TenantContext, true)`）；引擎池只經 `persistence.engine.EngineJdbc` 取得，交易一律 `@Transactional("engineTx")`／`TransactionTemplate` | RLS fail-closed；連線池重用不殘留租戶 |
| 2 | Store 依池拆分：引擎池 = `JdbcStateStore`、`JdbcTimer`、`JdbcOutboxStore`、`JdbcDeliveryTaskStore`、`JdbcEventStore` 的派發方法（拆成 `JdbcEventQueue`）、`JdbcJourneyDefinitionStore` 的 `find`／`findActiveEventSources`（拆成 `JdbcJourneyCatalog`）；其餘（攝取 insert、`JdbcEnrollmentQueryStore`、旅程建立、客戶、租戶）走應用池。`ArchitectureTest` 加：只有 `..persistence.engine..` 可依賴 `EngineJdbc`；`..query..`、`..security..`、`..profile..`、`..tenancy..` 不可依賴 `..persistence.engine..`；引擎套件的 `@Transactional` 必須指定 `engineTx` | 一個類別只用一個池，混用會靜默失去原子性 |
| 3 | 認證（`tenancy-auth.md`）：`SecurityConfig` 改寫；`ApiKeyAuthenticationFilter` 以 SHA-256 查 `tenant_api_key`、平台金鑰以 `SecretMatcher` 比對 `CDP_PLATFORM_API_KEY`；`TenantContextFilter`（認證之後）解析租戶並設 `TenantContext`（ThreadLocal，請求結束清除）與角色；`RoleHierarchy` TENANT_ADMIN > EDITOR > VIEWER；BCrypt（`spring-security-crypto`，已在 starter 內，不加相依）；帳號不存在時也跑一次 BCrypt；CSRF：`csrf.spa()`（Spring Security 7），`ignoringRequestMatchers` 排除帶 `Authorization` 的請求與 login／logout，`SameSite` 以 `CookieCsrfTokenRepository.setCookieCustomizer` 設定（`spa()` 不接受自訂 repository 時改用同版文件的 `withHttpOnlyFalse()`＋SPA handler 寫法，行為以 `tenancy-auth.md` §1.1 為準）；session 仍在記憶體 | 多租戶後 session 也要寫入（畫布），v0.1.0 關 CSRF 的前提不再成立 |
| 4 | 過渡：BE-06 期間 `TenantContext` 一律取 `CdpProperties.tenantId`（單租戶，既有驗收不變）；BE-07 改為每請求解析並移除 `CDP_TENANT_ID` 與 D-020 變數；V5 以既有資料回填 `tenant` | 一次只換一層，每張單 `make check` 都綠 |
| 5 | ProfileStore（`profiles.md`）：`com.cdp.profile`（Controller、`ProfileService`、`IdentifierNormalizer`、`ProfileSearchService`）；引擎用的 `engine.ProfileStore.getFields`（spec 04 §8 子集）由 `persistence.engine.JdbcProfileStore` 實作；`EventIngestionService` 攝取時 `insert … on conflict do nothing` 建空白客戶；遮罩重用 `PiiMasker`（根路徑 `attributes`、`identifiers[i].value`） | 搜尋與條件判斷共用一份客戶資料 |
| 6 | 圖形軌跡：後端只加 `journeys.md` §2 與 `/enrollments` 瀏覽（V6 加 `ix_enrollment_recent`、`ix_enrollment_journey_recent`），路徑由前端依 journey-engine §4.5 推導；前端用 `@xyflow/react` 12.12.0＋`@dagrejs/dagre` 3.1.1（皆 MIT，FE-03 加進相依〔D-009〕；`docs/research/canvas-library.md`）唯讀模式；`layout` 為 `null` 或缺節點時以 dagre 自動排版（左到右、`acyclicer: greedy`），回邊用自訂 edge 元件；不用 elkjs（EPL-2.0／GPL-3.0，與 D-008 相容性未確認） | 第二版畫布沿用同一元件 |
| 7 | 第二版生命週期（`journeys.md` §3）：`journey` 表管狀態與草稿、`journey_definition` 仍是不可變版本；`JourneyService.validate` 抽成 `JourneyGraphValidator`（規則 7 改 SCC 檢查；回 `Issue` 帶規則編號與節點，`journeys.md` §3）；Schema 層加 `com.networknt:json-schema-validator` 3.0.8（Apache-2.0，依賴 Jackson 3）驗 `journey-definition.schema.json`，不覆寫 Boot BOM 的 Jackson 版本，跑不起來就維持 Java 規則（需調查 6）；POST `/journeys` 同步 `journey` 列（`journeys.md` §3.1）；`findActiveEventSources` 改以 `journey.status = ACTIVE` 的 `latest_version`；`JourneyStatusKillSwitch` 改讀 `journey.status`；停止 = 一個交易設 `ABORTED`＋`OutboxStore.cancelPendingByJourney`，`JourneyStopSweeper`（第四個迴圈，引擎池）分批把 Enrollment 轉 `ABORTED` 並寫軌跡 | 版本不可變才能綁定；停止不卡 relay（#72 E） |
| 8 | 第二版分支（`journeys.md` §4）：`engine.node.builtin` 加 `WaitForEventNode`、`ConditionNode`（純函式 `engine.node.condition.ConditionEvaluator`）、`SplitTestNode`、`MergeNode`、`ExitNode`、`GoalNode`；`DefaultEnrollmentAdvancer` 步驟 3 呼叫 `engine.runtime.SettingsEvaluator`（goal／global_exit）、步驟 4 依 `requirements()` 以 `ProfileStore.getFields` 預查；`AdvanceLoop` 對 `EVENT` 等待送 `Timeout`；`EventDispatcher` 每個事件在建立之後：`findWaitingForEvent` → `signal(EventArrived)`、`findOpenByProfile` → goal／exit 事件條件；`StateStore` 加 `lockById`、`findOpenByProfile`；進入終態時 `save` 同交易 `cancelPendingByEnrollment`；`decision_reason` 不引用屬性值（契約 §4.3），值放 `evaluated` | 節點維持純函式（spec 04） |
| 9 | 第二版 G1／G3：`TriggerSnapshotFactory` 依 spec 02 §5 截斷、`profile_snapshot` 只放 Condition 與 global_exit 引用的欄位；重入在 `EngineJourneyRuntime.start`（spec 02 §6「落地」，`trigger_event_id` 取 `TriggerSnapshot.eventId`，介面不變） | 派發冪等與 G3 分開 |
## relay 與迴圈（#72、#44；不需 migration）
| 項目 | 設計 |
|---|---|
| `OutboxRelay.runOnce` | spec 07 §12「relay」：搶列（短交易）→ 交易外 `convertAndSend`＋`CorrelationData` 整批送出（拿掉 `rabbit.invoke`）→ 一起等 future 到 `OUTBOX_RELAY_CONFIRM_TIMEOUT` → 一個小交易 `batchUpdate` 條件式回寫；`OutboxStore` 改為 `claimDue(now, leaseUntil, limit)`、`markPublished(key, leaseUntil, at)`／`reschedule(key, leaseUntil, at)` 回傳是否生效、`cancelPendingByEnrollment`／`ByJourney`，移除 `lockDue`；`FaultPoint` 加 `RELAY_BEFORE_WRITEBACK` |
| 執行緒與小交易 | `spring.task.scheduling.pool.size` = `CDP_SCHEDULER_POOL_SIZE`（啟動驗證 ≥ 迴圈數）；`AdvanceLoop` 每筆一個交易（`lockDue(now, 1, 本輪失敗的 id)` → `advance`，失敗者本輪略過、下輪重試），`EventDispatcher` 每個事件一個交易；DESIGN-01 #12「一條推進鏈一個交易」不變，取消「一批一個交易」；三個迴圈都「滿批續做」到 `CDP_LOOP_MAX_DRAIN` |
| broker 與關機 | `spring.rabbitmq.connection-timeout` = `RABBITMQ_CONNECTION_TIMEOUT`；listener container `shutdownTimeout` = `DELIVERY_SHUTDOWN_TIMEOUT`；consumer 另查 outbox 列 `CANCELLED` → `delivery_task.CANCELLED` |
| 驗收（QA-02） | 沿用兩處中斷恰好一則；新增：lease 過期重發恰好一則（`RELAY_AFTER_CONFIRM` 後快轉超過 lease）；遲到 confirm 不覆寫（`RELAY_BEFORE_WRITEBACK` 期間另一輪重搶並標 `PUBLISHED`，`publish_count` = 1）；relay 卡住時計時器照常推進；3 倍批量到期一個 tick 內清空；lease ≤ confirm 逾時拒絕啟動。第二版（QA-07）：停止時在途的列維持 `CANCELLED`、Mock 0 則 |
| 壓測（QA-06，D-038） | 1 萬、10 萬筆同時到期的清空時間與各迴圈每秒筆數；子交易溢位：壓測前 `pg_stat_reset_slru('subtransaction')`，記錄 `pg_stat_slru` 中 `name = 'subtransaction'` 的 `blks_read`／`blks_zeroed` 增量、取樣 `pg_stat_activity` 的 `wait_event = 'SubtransSLRU'` 次數、各 backend `pg_stat_get_backend_subxact()` 的 `subxact_overflow`（backend id 取法 QA-06 實測）；積壓下 `lockDue`、`claimDue`、派發佇列的 `EXPLAIN (ANALYZE, BUFFERS)`；壓測前後 `effect_outbox`、`event`、`enrollment_record` 的 `n_dead_tup` 與表／索引大小；confirm 延遲分布。`make perf` 執行，不進 `make check` |
## 設定（寫進 .env.example，這裡不寫值）
| 變數 | 用途 | 機密 |
|---|---|---|
| `CDP_DB_APP_USER`、`CDP_DB_APP_PASSWORD`；`CDP_PLATFORM_API_KEY`；`CDP_PHONE_DEFAULT_COUNTRY_CODE` | 做法 #1；`tenancy-auth.md` §1；`profiles.md` §1.1 | 密碼、金鑰是 |
| `OUTBOX_RELAY_CONFIRM_TIMEOUT`、`OUTBOX_RELAY_LEASE`、`RABBITMQ_CONNECTION_TIMEOUT`、`DELIVERY_SHUTDOWN_TIMEOUT`、`CDP_SCHEDULER_POOL_SIZE`、`CDP_LOOP_MAX_DRAIN` | 「relay 與迴圈」 | 否 |
| BE-07 移除：`CDP_TENANT_ID`、`CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`、`CDP_SUPPORT_USERNAME`、`CDP_SUPPORT_PASSWORD` | 做法 #4 | — |

## 派工順序與檔案分工（D-022 一次一到兩張實作單；qa 與對應實作單同批；驗收目錄 `T/acceptance/<區>/`、`web/tests/acceptance/<區>/`）
| 批 | 單 | 角色 | 內容 | 相依 | 負責檔案（隨單授權的跨界檔） | 驗收目錄 |
|---|---|---|---|---|---|---|
| 1 | BE-05＋QA-02 | backend／qa | relay 與迴圈 | — | `J/delivery/`、`J/engine/runtime/AdvanceLoop`、`J/ingest/EventDispatcher`、`J/persistence/JdbcOutboxStore`、`J/common/fault/`；`application.yml`、`application-test.yml` | `delivery/` |
| 1 | FE-02＋QA-03 | frontend／qa | 外框、登入、工作區切換、受眾佔位；client 帶 `X-Tenant-Id`、`X-XSRF-TOKEN`（對契約 mock） | UI-03 | `web/src/`（`tokens.css` 除外） | `shell/` |
| 2 | BE-06＋QA-04a | backend／qa | V5、雙池、`TenantAwareTransactionManager`、Store 拆分 | BE-05 | `J/`、`V5__tenancy_rls.sql`、`application*.yml`、`T/architecture/` | `tenancy/`（RLS 目錄檢查、`cdp_app` 讀不到他租戶、測試連線非 superuser） |
| 3 | BE-07＋QA-04b | backend／qa | `tenancy-auth.md` 全部；`tools/demo-seed/` 改用平台 API | BE-06 | `J/security/`、`J/tenancy/`、`tools/demo-seed/`、`.env.example`（只刪做法 #4 的變數） | `security/`（改寫既有） |
| 3 | FE-03＋QA-05a | frontend／qa | 旅程列表、Enrollment 瀏覽、客戶搜尋→客戶頁→圖形軌跡 | FE-02 | `web/src/`、`web/package.json`、`package-lock.json` | `customer/`、`trace-graph/` |
| 4 | BE-08＋QA-05b | backend／qa | V6、`profiles.md`、`journeys.md` §2、`/enrollments` 瀏覽 | BE-07 | `J/profile/`、`J/journey/`、`J/query/`、`V6__profile_store.sql` | `profile/`、`journey/` |
| 5 | QA-06、INT-02 | qa／frontend | 壓測（含 `Makefile` 的 `perf`、`docs/perf/`）；整合與 demo-seed 端到端 | 1～4 | 依單 | — |
| 二 | BE-09＋QA-07、FE-04 → BE-10＋QA-08、FE-05 → BE-11、INT-03 | | 做法 #7 → #8 → #9；FE-04 畫布對 `journeys.md` §3 mock | 第一版 | `V7__journey_lifecycle.sql` 歸 BE-09 | `journey/`、`engine/`、`canvas/` |

## 待甲方決定／需要調查
- 待決定：1 拆版（上表）；2 D-019 改為每租戶 `INGEST`／`ADMIN` 金鑰（DB 存雜湊、平台 API 發放）——多租戶下單一金鑰無法推導租戶（spec 01 §3）；3 角色採 `VIEWER`／`EDITOR`／`TENANT_ADMIN`；4 帳號開通只走平台 API（＋demo-seed），第一版無成員管理畫面；5 登入限速（#40）是否併入第一版（建議併入：改成真人帳號後暴力破解風險上升）；6 第二版驗收用「場景一縮減版」：拿掉 `WebhookNode`、`UpdateProfileNode`（不在本輪範圍），`split_1` 的 coupon 直連 `send_coupon`；7 `CDP_PHONE_DEFAULT_COUNTRY_CODE` 填值；8 「設定」第二列採團隊建議值（同 D-027）
- 需調查：1～5 已結案（`docs/research/v0.2.0-design-questions.md`、`canvas-library.md`；落點：1 → 做法 #1、spec 07 §12；2 → 做法 #6；3 → 壓測列；4 → 做法 #3；5 → 做法 #7）；6 `json-schema-validator` 3.0.8 依賴 Jackson 3.2.1，與 Boot 4.1.1 BOM 管的 Jackson（本機 Maven 快取為 3.1.5）能否並用——BE-09 開工時實測，不行則維持 Java 規則

## 風險
| 風險 | 對策 |
|---|---|
| Testcontainers 的 PG 帳號是 superuser，測試若用它連線 RLS 會被繞過而全綠 | 應用池一律用 `CDP_DB_APP_USER`；QA-04a 斷言登入帳號與兩個 role 的 `rolbypassrls`、`rolsuper` 皆 false，且每張 RLS 表都有 `tenant_isolation`、`engine_all` |
| 混用兩個交易管理器會靜默失去原子性 | 做法 #2 的 ArchUnit 規則；BE-06 review 重點 |
| BE-06／07 牽動幾乎所有既有驗收測試；global_exit 屬性條件不即時（spec 03 §3「落地」）；遮罩白名單全平台一份 | qa 同批改寫、過渡見做法 #4；後兩項文件已註明，受眾版本與每租戶設定再議 |
