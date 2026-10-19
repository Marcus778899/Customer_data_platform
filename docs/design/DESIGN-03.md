# 設計：v0.2.0 登入與角色、客戶、分支引擎、畫布與 relay 改寫（DESIGN-03，單一租戶改版）
- 契約：`docs/contracts/auth.md`、`profiles.md`、`journeys.md`（新）；`journey-engine.md`（§4 瀏覽、§4.5 圖形軌跡、§5 新錯誤碼）；表見 spec 07 §12；G3、喚醒見 spec 02 §4、§6「落地」；SplitTest、驗證、global_exit 見 spec 03；停止見 spec 05 §5「落地」；外部 review 見 #72（D-036～D-038）；代管單一租戶與整合入口見 D-051、D-052、DESIGN-04；`J`、`T` 同 DESIGN-01

## 拆版（D-055 定案 A；單數 = 「派工順序」表的單號數，qa 單與 a／b 各算一張，不含 UI-03）
| 方案 | 第一版（v0.2.0） | 第二版（v0.3.0） | 單數 |
|---|---|---|---|
| **A（定案）** | relay／迴圈改寫＋壓測、使用者登入與角色、ProfileStore＋客戶搜尋＋客戶頁、外框分頁＋受眾佔位、旅程列表（唯讀）、最近 Enrollment 瀏覽（D-030）、圖形軌跡（直線旅程） | 旅程生命週期（草稿、送審與審核〔D-056〕、停止）、畫布（D-018）、分支節點＋goal／global_exit、G1、G3、分支與循環軌跡、節點統計 | 12＋9 |
- 驗收：A 第一版 = 三種角色依 `auth.md` §2 矩陣放行／拒絕、停用與改角色下一請求生效、不可移除最後一位 `ADMIN`、四種方式搜到客戶並開圖形軌跡、relay 新情境（下表）全綠、壓測報告；第二版 = 場景一縮減版（D-046）、同 `cart_id` 5 次只建 1 個、以畫布建出場景一、送審後由另一位管理員核准上線（退回附原因、改草稿再送審；送審中草稿鎖定）、v1 等待中核准 v2 不受影響
- 算法：A 第一版 12 = 批 1～4（BE-05、QA-02、FE-02、QA-03、BE-06、QA-04、FE-03、QA-05a、BE-07、QA-05b、QA-06、INT-02），第二版 9 = 「二」列（審核 D-056 併入 BE-08＋QA-07，前端另開 FE-06）（未採的 B、C 為 16＋5、21）。前版 14＋8 中的多租戶底座單（舊 BE-06〔RLS、雙池〕＋QA-04a）因 D-051 刪除。A、B 都讓計劃書 Phase 2 起順延一版號（受眾變 v0.5.0，牽動 D-034）；A 另把畫布移到 v0.3.0（牽動 D-018）。v0.2.0 沒有審核：列表 `review_status` 一律 `null`、不顯示「待審核」，`POST /journeys` 只收管理金鑰（`auth.md` §2），故 v0.2.0 不會有未經審核的使用者發佈
## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 單一租戶（D-051）：**保留**所有表的 `tenant_id` 欄位與以它開頭的 PK／索引，值一律 `CDP_TENANT_ID`（v0.1.0 起既有，`CdpProperties.tenantId`）；新表 `profile`、`profile_identifier`、`journey` 同樣帶；`app_user` 不帶（公司層級）。不做 RLS、DB role、雙連線池、`X-Tenant-Id`、平台 API、每租戶金鑰（D-019 維持）；連線維持現有單一連線池（spec README「RLS 的唯一例外」保留為回到多租戶時的做法）。**例外：D-052 整合介面表**（landing／outbound，DESIGN-04）不帶 `tenant_id`：核心吸收寫入核心表時補 `CDP_TENANT_ID`、寫 outbound 時只讀本環境的列；ETL 不得直寫核心表；spec 07 §2 的 RLS 目錄檢查只看帶 `tenant_id` 的表，不受影響；回到多租戶時介面表的租戶歸屬由 DESIGN-04 另定 | 既有七張表 PK 含 `tenant_id`、spec 04 介面全帶 `tenantId`，拿掉要改全部 PK 與查詢且無功能收益；固定值只多一欄；回到多租戶時只需加成員表＋RLS，不必回填；介面表是一套環境對外的邊界，ETL 不必知道內部租戶值 |
| 2 | 認證（`auth.md`）：`SecurityConfig` 改寫；`ApiKeyAuthenticationFilter` 不變（兩把 env 金鑰，管理金鑰對應角色 `ADMIN`）；`AuthController.login` 改查 `app_user`（新套件 `com.cdp.user`：`AppUserStore`〔JDBC〕、`UserService`、`UserController`），BCrypt（`spring-security-crypto`，已在 starter 內，不加相依），帳號不存在也跑一次 BCrypt；session 只存 `user_id`，每請求由 `UserService` 載入使用者決定角色與停用；`RoleHierarchy` `ADMIN` > `EDITOR` > `VIEWER`；`LAST_ADMIN` 檢查與更新同一交易（`SELECT … FOR UPDATE` 啟用中的 ADMIN）；CSRF：`csrf.spa()`（Spring Security 7），`ignoringRequestMatchers` 排除帶 `Authorization` 的請求與 login／logout，`SameSite` 以 `CookieCsrfTokenRepository.setCookieCustomizer` 設定（`spa()` 不接受自訂 repository 時改用同版文件的 `withHttpOnlyFalse()`＋SPA handler，行為以 `auth.md` §1.1 為準）；session 仍在記憶體；`AuthProperties` 移除 D-020 帳密 | session 要能寫入（畫布、使用者管理），v0.1.0 關 CSRF 的前提不再成立；停用要立即生效 |
| 3 | 過渡：BE-06 合併前 `auth/login` 仍收 `username`（journey-engine §1.2）；BE-06 加 V5（只建 `app_user`，不動既有表）、移除 `CDP_SUPPORT_USERNAME`／`CDP_SUPPORT_PASSWORD`，`tools/demo-seed/` 以管理金鑰經 `POST /users` 建三種角色的示範帳號；既有驗收中用客服帳密登入的測試由 QA-04 同批改寫 | 一次只換一層，每張單 `make check` 都綠 |
| 4 | ProfileStore（`profiles.md`）：`com.cdp.profile`（Controller、`ProfileService`、`IdentifierNormalizer`、`ProfileSearchService`）；所有寫入（§2 API、事件攝取建空白客戶 `insert … on conflict do nothing`）只經 `ProfileWriter`（合併、正規化、衝突判定的唯一實作）；引擎用的 `engine.ProfileStore.getFields`（spec 04 §8 子集）由 `persistence.JdbcProfileStore` 即時查表、不快取；遮罩重用 `PiiMasker`（根路徑 `attributes`、`identifiers[i].value`） | 搜尋與條件判斷共用一份客戶資料 |
| 5 | 與 D-052 整合入口不衝突（細節由 DESIGN-04 定）：不變條件放 DB（spec 07 §12：識別碼 PK、正規化 CHECK、屬性大小 CHECK），ETL 不論寫哪裡都擋得住髒資料；`ProfileWriter` 是日後吸收整合資料時重用的入口；讀取不快取，其他來源寫入後下一次推進即可見（同 spec 03 §3「落地」的不即時語意）；本版不建整合用的表、schema、DB 帳號，不在 `profile` 加觸發器，也不假設「只有 API 會寫」 | 先定規則、不定管道：DESIGN-04 選 staging 吸收或直寫都不必改本版的表 |
| 6 | 圖形軌跡：後端只加 `journeys.md` §2 與 `/enrollments` 瀏覽（V6 加 `ix_enrollment_recent`、`ix_enrollment_journey_recent`），路徑由前端依 journey-engine §4.5 推導；前端用 `@xyflow/react` 12.12.0＋`@dagrejs/dagre` 3.1.1（皆 MIT，FE-03 加進相依〔D-009〕；`docs/research/canvas-library.md`）唯讀模式；`layout` 為 `null` 或缺節點時以 dagre 自動排版（左到右、`acyclicer: greedy`），回邊用自訂 edge 元件；不用 elkjs（EPL-2.0／GPL-3.0，與 D-008 相容性未確認） | 第二版畫布沿用同一元件 |
| 7 | 第二版生命週期（`journeys.md` §3）：`journey` 表管狀態與草稿、`journey_definition` 仍是不可變版本；`JourneyService.validate` 抽成 `JourneyGraphValidator`（規則 7 改 SCC 檢查；`Issue` 帶規則編號與節點）；Schema 層加 `com.networknt:json-schema-validator` 3.0.8（Apache-2.0，依賴 Jackson 3）驗 `journey-definition.schema.json`，不覆寫 Boot BOM 的 Jackson，跑不起來就維持 Java 規則（需調查 1）；POST `/journeys` 同步 `journey` 列（`journeys.md` §3.1）；`findActiveEventSources` 改以 `journey.status = ACTIVE` 的 `latest_version`；`JourneyStatusKillSwitch` 改讀 `journey.status`；停止 = 一個交易設 `ABORTED`＋`OutboxStore.cancelPendingByJourney`，`JourneyStopSweeper`（第四個迴圈）分批把 Enrollment 轉 `ABORTED` 並寫軌跡 | 版本不可變才能綁定；停止不卡 relay（#72 E） |
| 8 | 第二版分支（`journeys.md` §4）：`engine.node.builtin` 加 `WaitForEventNode`、`ConditionNode`（純函式 `engine.node.condition.ConditionEvaluator`）、`SplitTestNode`、`MergeNode`、`ExitNode`、`GoalNode`；`DefaultEnrollmentAdvancer` 步驟 3 呼叫 `engine.runtime.SettingsEvaluator`（goal／global_exit）、步驟 4 依 `requirements()` 以 `ProfileStore.getFields` 預查；`AdvanceLoop` 對 `EVENT` 等待送 `Timeout`；`EventDispatcher` 每個事件在建立之後：`findWaitingForEvent` → `signal(EventArrived)`、`findOpenByProfile` → goal／exit 事件條件；`StateStore` 加 `lockById`、`findOpenByProfile`；進入終態時 `save` 同交易 `cancelPendingByEnrollment`；`decision_reason` 不引用屬性值，值放 `evaluated` | 節點維持純函式（spec 04） |
| 9 | 第二版 G1／G3：`TriggerSnapshotFactory` 依 spec 02 §5 截斷、`profile_snapshot` 只放 Condition 與 global_exit 引用的欄位；重入在 `EngineJourneyRuntime.start`（spec 02 §6「落地」，`trigger_event_id` 取 `TriggerSnapshot.eventId`，介面不變） | 派發冪等與 G3 分開 |
| 10 | 第二版審核（D-056；`journeys.md` §3.2、§3.3）：`com.cdp.journey` 加 `JourneyReviewService`（送審、核准、退回、撤回）與 `JourneyReviewStore`（JDBC，表 `journey_review`）；核准 = 一個交易：`journey` 列 `FOR UPDATE` → 條件式 `UPDATE journey_review … WHERE status = 'PENDING'`（0 列 → `REVIEW_NOT_PENDING`）→ `JourneyGraphValidator` 重驗 → 建 `journey_definition` 版本並清草稿；D-060 的三條規則只在 `ReviewPolicy` 一個類別實作（對應 §3.3 三列），甲方選 B 只改它與契約 §3.3；停止同交易把 `PENDING` 轉 `WITHDRAWN` | 審核內容 = 送審 `revision`（草稿鎖定）；差異集中一處 |
## relay 與迴圈（#72、#44；不需 migration）
| 項目 | 設計 |
|---|---|
| `OutboxRelay.runOnce` | spec 07 §12「relay」：搶列（短交易）→ 交易外 `convertAndSend`＋`CorrelationData` 整批送出（拿掉 `rabbit.invoke`）→ 一起等 future 到 `OUTBOX_RELAY_CONFIRM_TIMEOUT` → 一個小交易 `batchUpdate` 條件式回寫；`OutboxStore` 改為 `claimDue(now, leaseUntil, limit)`、`markPublished(key, leaseUntil, at)`／`reschedule(key, leaseUntil, at)` 回傳是否生效、`cancelPendingByEnrollment`／`ByJourney`，移除 `lockDue`；`FaultPoint` 加 `RELAY_BEFORE_WRITEBACK` |
| 執行緒與小交易 | `spring.task.scheduling.pool.size` = `CDP_SCHEDULER_POOL_SIZE`（啟動驗證 ≥ 迴圈數）；`AdvanceLoop` 每筆一個交易（`lockDue(now, 1, 本輪失敗的 id)` → `advance`，失敗者本輪略過、下輪重試），`EventDispatcher` 每個事件一個交易；DESIGN-01 #12「一條推進鏈一個交易」不變，取消「一批一個交易」；三個迴圈都「滿批續做」到 `CDP_LOOP_MAX_DRAIN` |
| broker 與關機 | `spring.rabbitmq.connection-timeout` = `RABBITMQ_CONNECTION_TIMEOUT`；listener container `shutdownTimeout` = `DELIVERY_SHUTDOWN_TIMEOUT`；consumer 另查 outbox 列 `CANCELLED` → `delivery_task.CANCELLED` |
| 驗收（QA-02） | 沿用兩處中斷恰好一則；新增：lease 過期重發恰好一則（`RELAY_AFTER_CONFIRM` 後快轉超過 lease）；遲到 confirm 不覆寫（`RELAY_BEFORE_WRITEBACK` 期間另一輪重搶並標 `PUBLISHED`，`publish_count` = 1）；relay 卡住時計時器照常推進；3 倍批量到期一個 tick 內清空；lease ≤ confirm 逾時拒絕啟動。第二版（QA-07）：停止時在途的列維持 `CANCELLED`、Mock 0 則 |
| 壓測（QA-06，D-038） | 1 萬、10 萬筆同時到期的清空時間與各迴圈每秒筆數；子交易溢位：壓測前 `pg_stat_reset_slru('subtransaction')`，記錄 `pg_stat_slru` 中 `name = 'subtransaction'` 的 `blks_read`／`blks_zeroed` 增量、取樣 `pg_stat_activity` 的 `wait_event = 'SubtransSLRU'` 次數、各 backend `pg_stat_get_backend_subxact()` 的 `subxact_overflow`；積壓下 `lockDue`、`claimDue`、派發佇列的 `EXPLAIN (ANALYZE, BUFFERS)`；壓測前後 `effect_outbox`、`event`、`enrollment_record` 的 `n_dead_tup` 與表／索引大小；confirm 延遲分布。`make perf` 執行，不進 `make check` |
## 設定（寫進 .env.example；建議值 = `.env.example` 預設，D-045、D-047 採用）
| 變數 | 用途 | 建議值 | 機密 |
|---|---|---|---|
| `CDP_PHONE_DEFAULT_COUNTRY_CODE` | `profiles.md` §1.1 | 甲方填 | 否 |
| `OUTBOX_RELAY_CONFIRM_TIMEOUT`、`OUTBOX_RELAY_LEASE`、`RABBITMQ_CONNECTION_TIMEOUT`、`DELIVERY_SHUTDOWN_TIMEOUT`、`CDP_SCHEDULER_POOL_SIZE`、`CDP_LOOP_MAX_DRAIN` | 「relay 與迴圈」（D-047）；lease 必須 > confirm 逾時（啟動驗證） | PT5S、PT30S、PT10S、PT30S、4（第二版四個迴圈）、PT10S | 否 |
| `CDP_LOGIN_MAX_FAILURES`、`CDP_LOGIN_LOCKOUT` | 登入限速（D-045，`auth.md` §3） | 5、PT15M | 否 |
| 保留：`CDP_TENANT_ID`、`CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`、`CDP_SESSION_IDLE_TIMEOUT`；BE-06 移除：`CDP_SUPPORT_USERNAME`、`CDP_SUPPORT_PASSWORD` | 做法 #1～#3 | 不變 | 金鑰是 |

## 派工順序與檔案分工（D-022 一次一到兩張實作單；qa 與對應實作單同批；驗收目錄 `T/acceptance/<區>/`、`web/tests/acceptance/<區>/`）
| 批 | 單 | 角色 | 內容 | 相依 | 負責檔案（隨單授權的跨界檔） | 驗收目錄 |
|---|---|---|---|---|---|---|
| 1 | BE-05＋QA-02 | backend／qa | relay 與迴圈 | — | `J/delivery/`、`J/engine/runtime/AdvanceLoop`、`J/ingest/EventDispatcher`、`J/persistence/JdbcOutboxStore`、`J/common/fault/`；`application.yml`、`application-test.yml` | `delivery/` |
| 1 | FE-02＋QA-03 | frontend／qa | 外框、email 登入、受眾佔位；client 帶 `X-XSRF-TOKEN`（對 `auth.md` mock） | UI-03 | `web/src/`（`tokens.css` 除外） | `shell/` |
| 2 | BE-06＋QA-04 | backend／qa | V5、`auth.md` 全部（含 `/users`）、demo-seed 建帳號、登入限速（D-045） | BE-05 | `J/security/`、`J/user/`、`V5__app_user.sql`、`tools/demo-seed/`、`application*.yml`、`.env.example`（刪做法 #3 的變數；登入限速兩變數已由本單加入，BE-06 接上） | `security/`（改寫既有） |
| 2 | FE-03＋QA-05a | frontend／qa | 旅程列表、Enrollment 瀏覽、客戶搜尋→客戶頁→圖形軌跡 | FE-02 | `web/src/`、`web/package.json`、`package-lock.json` | `customer/`、`trace-graph/` |
| 3 | BE-07＋QA-05b | backend／qa | V6、`profiles.md`、`journeys.md` §2、`/enrollments` 瀏覽 | BE-06 | `J/profile/`、`J/journey/`、`J/query/`、`J/ingest/EventIngestionService`、`J/persistence/JdbcProfileStore`、`V6__profile_store.sql` | `profile/`、`journey/` |
| 4 | QA-06、INT-02 | qa／frontend | 壓測（含 `Makefile` 的 `perf`、`docs/perf/`）；整合與 demo-seed 端到端 | 1～3 | 依單 | — |
| 二 | BE-08＋QA-07、FE-04、FE-06 → BE-09＋QA-08、FE-05 → BE-10、INT-03 | | 做法 #7＋#10 → #8 → #9；FE-04 畫布、FE-06 審核畫面（送審、待審清單、核准／退回、紀錄）皆對 `journeys.md` §3 mock，FE-06 接在 FE-04 之後（同為 `web/src/`） | 第一版 | `V7__journey_lifecycle.sql` 歸 BE-08 | `journey/`、`engine/`、`canvas/`、`review/` |

## 待甲方決定／需要調查
- 待決定：D-060 審核細節（依建議 1A、2A 設計，差異只在 `journeys.md` §3.3、做法 #10 `ReviewPolicy`）；新（請 manager 編號）：管理金鑰直接建立版本是否免審（`journeys.md` §3.3 第 3 列，建議保留給維運並留紀錄）
- 已定案的影響：D-055 → 上表與「二」列；D-056 → `auth.md` §2、`journeys.md` §3.2、做法 #10、FE-06；D-057 → `auth.md` §4；D-045 → `auth.md` §3 限速、上表兩變數、BE-06；D-047 → 上表 relay 六變數；D-046、D-048、D-050 已反映於驗收與契約；D-049 外框不再有工作區切換；D-019 維持單一金鑰
- 需調查：1 `json-schema-validator` 3.0.8 依賴 Jackson 3.2.1，與 Boot 4.1.1 BOM 管的 Jackson 能否並用——BE-08 開工時實測，不行則維持 Java 規則（不需甲方決定）

## 風險
| 風險 | 對策 |
|---|---|
| 沒有 RLS，漏寫 `tenant_id` 條件不會被擋 | 單一租戶下全表同一值，漏寫不外洩；回到多租戶前以 spec 07 §2 規則補 RLS 與目錄檢查 |
| ETL 直寫繞過 `ProfileWriter` 的合併語意 | 做法 #5：硬規則放 DB；合併語意由 DESIGN-04 的入口設計承接 |
| BE-06 牽動既有登入驗收；global_exit 屬性條件不即時（spec 03 §3「落地」） | qa 同批改寫、過渡見做法 #3；後者文件已註明 |
