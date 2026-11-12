# 設計：v0.3.0 旅程生命週期、畫布、分支引擎、遮罩預設、資安與 UX 修正（DESIGN-05）
- 契約：`journeys.md`（§3 骨架與 id、§4 `event_filter`、§5 節點統計、§6 範例）、`journey-engine.md`（§4.1 `profile_name`、§4.4 明文清單）、`auth.md`（§1 金鑰強度、§3 限速 v0.3.0）、`profiles.md`（§1 顯示名稱）；業務規則 `docs/domain/rules.md`；沿用 DESIGN-03 做法 #6～#10、「relay 與迴圈」、spec 07 §12 V8。`J` = `api/src/main/java/com/cdp`、`A` = `api/src/test/java/com/cdp/acceptance`、`W` = `web/src`、`WA` = `web/tests/acceptance`
## 做法（DESIGN-03 做法 #7～#10 不變，以下為新增或改動）
| # | 決策 | 理由 |
|---|---|---|
| 1 | `JourneyGraphValidator` 在 journey-lifecycle-api 就支援 §4 全部節點的驗證（畫布可先送審）；引擎執行在 journey-branch-engine 補上。兩單之間 develop 上核准含分支節點的版本會在推進時失敗，不發版 | 前端驗證回饋不等引擎 |
| 2 | 節點統計（`journeys.md` §5）獨立 `J/nodestats/`（Controller、Service、Store 介面）＋`J/persistence/JdbcNodeStatsStore`：一條 SQL 以 `enrollment_record (journey_id, journey_version)` join `node_trace` 分組計數（`ix_enrollment_journey_recent` 起頭），`statement_timeout` 5 秒；不加 migration、不快取 | 與 lifecycle 不共用檔，可平行 |
| 3 | 遮罩（#184，待決定 1 建議 A）：`MaskingProperties` 綁定值為 `null`（變數不存在）時用常數 `DEFAULT_PLAIN_KEYS = {name}`；`application.yml` 改 `${CDP_TRACE_PLAIN_KEYS:#{null}}`；`.env.example` 該行改註解；`Makefile` 照 `CDP_DEMO_PASSWORD` 的方式另 `export CDP_TRACE_PLAIN_KEYS`（未定義不 export）。`profile_name`（#180）在 `JdbcEnrollmentQueryStore` 列表 SQL `LEFT JOIN profile` 取 `attributes->>'name'`，`PiiMasker.isPlain("name")` 為假即 `null`；`profile_identifier` 同 SQL 取 `profile_identifier` 依 `(id_type, id_value)` 第一筆（`LATERAL … LIMIT 1`），以 `PiiMasker` 遮罩 | 白名單語意不變；已設定的環境（D-082 `name`）行為不變 |
| 4 | 金鑰強度（#152）：`AuthPropertiesValidator` 加 `CHANGE_ME`（不分大小寫）與長度 ≥ 32 檢查，常數不可由設定調低；`application-test.yml` 與驗收中的測試金鑰改成 ≥ 32 字的低熵值（例 `test-admin-key-` 補 `0`），避開 gitleaks `generic-api-key` | M-1 修法；不給降低防護的開關 |
| 5 | 登入限速（#153，待決定 2 建議 A）：`LoginRateLimiter` 鍵改 `(email, clientIp)`＋另一張 `clientIp` 計數；全域 `Semaphore(availableProcessors)` 包住 BCrypt；兩張表用有上限的 `LinkedHashMap`（access-order、`removeEldestEntry`，上限 100,000），過期清理只在插入時做；`clientIp` = `request.getRemoteAddr()`，`server.forward-headers-strategy: native`＋`server.tomcat.remoteip.internal-proxies: ${CDP_TRUSTED_PROXIES:…Tomcat 預設私有網段}` | M-2 修法；不加相依（不用 Caffeine） |
| 6 | #190：`docs/uat/artifacts/` 已由 1384f31 移出版控並 ignore；本單另加 `docs/uat/**/*.yml`、`docs/uat/**/*.yaml`（`*.log` 已全域 ignore），截圖 `docs/uat/<版本>/*.png` 照常進版控 | 防 uat 換輸出位置又被提交 |
| 7 | 不建程式骨架（本單禁區為程式碼）：每張實作單自建；共用檔依下表一次只歸一張，序列化避免重疊 | 派工單限制 |
## 業務規則與術語：新增 `docs/domain/glossary.md`（術語 18 條）、`rules.md`（審核 8 條、版本綁定、停止、節點、遮罩、驗收場景）；`README.md` 索引 9 條
## 設定（寫進 .env.example，這裡不寫值）：`CDP_TRACE_PLAIN_KEYS` 改選填（註解，做法 #3）；新增 `CDP_LOGIN_IP_MAX_FAILURES`、選填 `CDP_TRUSTED_PROXIES`（做法 #5，皆非機密）；`CDP_INGEST_API_KEY`、`CDP_ADMIN_API_KEY`（機密）說明加「≥ 32 字，`openssl rand -hex 32`」
## 威脅與對策
| 資產 | 威脅 | 對策 |
|---|---|---|
| 管理權限、帳號可用性、CPU | 佔位或短金鑰被猜中（M-1）；未登入者鎖他人帳號、大量 BCrypt、偽造 `X-Forwarded-For`（M-2） | 做法 #4 啟動即失敗；做法 #5，只信任 `CDP_TRUSTED_PROXIES` 的代理 |
| 旅程上線權 | 編輯者自行上線、自核、核准後被換內容、雙管理員競態 | DESIGN-03 #10：送審鎖草稿、`revision` 綁定、條件式 `UPDATE`、`SELF_APPROVAL_FORBIDDEN`、送審類只收 session |
| 客戶個資；引擎可用性 | 遮罩漏設、`profile_name` 帶出遮罩欄位、原因句帶屬性值；無等待迴圈、巨大草稿、統計大查詢 | 白名單內建、不明文即 `null`、journey-engine §4.3；規則 7、≤ 200 節點／256 KB、`statement_timeout` |
## 受影響的既有驗收測試
| 測試檔 | 跟新契約哪裡矛盾 | 交給哪張單改寫 |
|---|---|---|
| `A/common/FrameworkErrorFormatAcceptanceTest`、`A/delivery/DeliveryPipelineAcceptanceTest`、`DeliveryResilienceAcceptanceTest`、`A/engine/JourneyEngineAcceptanceTest`、`A/ingest/IngestAcceptanceBase`、`NulCharacterValidationAcceptanceTest`、`DispatchPropertiesStartupAcceptanceTest`、`A/relay-loop-rewrite/RelayLoopBase`、`RelayStartupValidationAcceptanceTest`、`A/security/StartupValidationAcceptanceTest`、`SecretToStringAcceptanceTest` | 寫死 < 32 字的金鑰（`test-admin-key`、`SECRET-aaaa-1111` 等），`auth.md` §1 金鑰強度後啟動失敗；改用共用常數（放 `A/support/`） | security-hardening-api |
| `A/user-auth-api/LoginRateLimitAcceptanceTest` | `auth.md` §3「不依來源 IP」改為 email＋IP；同一 IP 的既有情境結果不變，需確認跨 email 失敗數不觸發來源鎖定（測試設定調高 `CDP_LOGIN_IP_MAX_FAILURES`） | security-hardening-api |
| `WA/journey-customer-ui/fixtures.tsx`、`WA/integration/integration.test.tsx` | `EnrollmentSummary` 新增 `profile_name`、`profile_identifier`，`JourneySummary` 新增 `latest_review`；型別若設必填則 fixture 缺欄（建議前端型別設選填） | ux-polish-web（`JourneySummary` 部分歸 journey-review-ui） |
## 派工順序與檔案分工（D-022 一次一到兩張實作單；驗收目錄 = `A/<task_id>/`、`WA/<task_id>/`；整合 = `WA/integration/<task_id>/`＋`A/<task_id>/`）
| 批 | task_id | 角色 | 內容 | 負責檔案（含跨界檔） | 相依 | 驗收目錄 | 平行／fast |
|---|---|---|---|---|---|---|---|
| 1 | journey-lifecycle-api | backend | DESIGN-03 #7、#10、做法 #1；`journeys.md` §1～§3.5（含 `JourneySummary.latest_review`）、§4 驗證與 `field-keys`；V8 | `V8__journey_lifecycle.sql`、`J/journey/`、`J/persistence/JdbcJourney*`（新舊）、`JdbcOutboxStore`、`J/delivery/JourneyStatusKillSwitch`、`J/delivery/OutboxStore`、`J/engine/runtime/JourneyStopSweeper`（新）、`J/security/AccessRule`、`api/pom.xml`、`resources/schema/`、`application.yml` | — | `A/journey-lifecycle-api/` | 可與 node-stats、批 1 前端平行 |
| 1 | journey-node-stats-api | backend | 做法 #2、`journeys.md` §5 | `J/nodestats/`、`J/persistence/JdbcNodeStatsStore` | — | `A/journey-node-stats-api/` | 平行 |
| 1 | ux-fix-login-trace | frontend | #145（改 email 解除倒數，`auth.md` §3 末）、#147（開關關閉時摘要與原因句只顯示本地時間） | `W/pages/LoginPage.tsx`、`W/pages/trace/` | — | 無 | 平行；fast |
| 1 | ux-fix-customer-list | frontend | #144、#148、#178（程式聚焦不顯示外框，鍵盤 `:focus-visible` 照舊）、#179（列表、客戶頁沿用 `plain/` 的步驟說法）、#188 | `W/pages/CustomerPage.tsx`、`CustomerSearchPage.tsx`、`EnrollmentBrowsePage.tsx`、`W/components/ui.tsx`、`Shell.tsx`、`W/format.ts`、`W/plain/`、`W/styles/app.css` | — | 無 | 平行；fast |
| 2 | security-hardening-api | backend | 做法 #4、#5（待決定 2 後開工） | `J/security/AuthPropertiesValidator`、`LoginRateLimiter`、`LoginProperties`、`AuthController`、`ClientAddress*`（新）、`application.yml`、`application-test.yml`、`.env.example`、上表測試改寫由 qa 做 | lifecycle（`application.yml`） | `A/security-hardening-api/` | 與批 2 前端平行 |
| 2 | journey-canvas-ui | frontend | FE-04：草稿編輯、驗證結果標在節點、`field-keys`、`layout`（對 §3 mock） | `W/canvas/`（新）、`W/pages/JourneyEditorPage.tsx`（新）、`W/router.ts`、`W/api/`、`W/styles/app.css` | designer journey-builder-ui、批 1 fast | `WA/journey-canvas-ui/` | 平行 |
| 3 | masking-default-api | backend | 做法 #3（待決定 1 後開工）、`profile_name` | `J/query/MaskingProperties`、`PiiMasker`、`EnrollmentSummary`、`EnrollmentDetail`、`EnrollmentQueryService`、`J/persistence/JdbcEnrollmentQueryStore`、`application.yml`、`.env.example`、`Makefile` | security-hardening-api | `A/masking-default-api/` | |
| 3 | journey-review-ui | frontend | FE-06：送審、待審清單、核准／退回／撤回、審核紀錄、停止 | `W/pages/`（審核相關新檔）、`W/router.ts`、`W/api/`、`W/components/Shell.tsx`、`W/styles/app.css` | canvas-ui | `WA/journey-review-ui/` | |
| 4 | journey-branch-engine | backend | DESIGN-03 #8＋D-085 `EventSourceNode.event_filter`（`ConditionEvaluator` 依 `journeys.md` §4「條件欄位的根」「比較語意」） | `J/engine/`、`J/ingest/EventDispatcher`、`J/persistence/JdbcStateStore`、`J/persistence/JdbcJourneyDefinitionStore` | lifecycle | `A/journey-branch-engine/` | |
| 4 | trace-branch-ui | frontend | FE-05：分支與迴圈軌跡（journey-engine §4.5）、旅程頁節點統計 | `W/graph/`、`W/pages/JourneyPage.tsx`、`TracePage.tsx`、`W/pages/trace/`、`W/api/` | review-ui、node-stats（可 mock） | `WA/trace-branch-ui/` | |
| 5 | enrollment-reentry-engine | backend | DESIGN-03 #9（G1、G3、`reentry`） | `J/engine/runtime/`、`J/engine/model/` | branch-engine | `A/enrollment-reentry-engine/` | |
| 5 | ux-polish-web | frontend | #146 首頁與導覽標示、#150 favicon、#180 客戶欄、#187 客戶頁標題（依 designer 規格） | `W/router.ts`、`W/App.tsx`、`W/components/Shell.tsx`、`W/pages/CustomerPage.tsx`、`EnrollmentBrowsePage.tsx`、`SearchPage.tsx`、`W/api/types.ts`、`web/index.html` | designer ux 規格、masking-default-api（可 mock） | `WA/ux-polish-web/` | |
| 5 | journey-draft-review-scope-api | backend | `journeys.md` §3「這份草稿的審核」（自行決定表）：V9 `journey.draft_created_at`（寫入、讀取、down 見 spec 07 §12「V9」），`review_status`、`latest_review`、`Draft.latest_review` 都用它界定。驗收：退回→刪草稿→`PUT revision 0`，三處皆 `null` 且不在 `?review_status=REJECTED`；退回→改草稿仍 `REJECTED`（回歸）；撤回→刪→新草稿 `null`；退回後金鑰經 §3.1 建版本，草稿與列表仍 `REJECTED` | `V9__journey_draft_created_at.sql`（新）、`J/journey/`、`J/persistence/JdbcJourneyStore`、`JdbcJourneyQueryStore`、`JdbcJourneyReviewStore`（不含 `JdbcJourneyDefinitionStore`）（欄位語意見 spec 07 §12「V9」） | lifecycle-service（已合併） | `A/journey-draft-review-scope-api/` | 可與 branch-engine、前端平行（不同檔）；前端型別不變 |
| 6 | journey-builder-integration | frontend | 接真 API、demo-seed 加縮減版場景一與迴圈旅程；API 端到端：畫布產出的定義送審→他人核准→事件→送達／`GOAL_MET`、v1／v2 並存、停止 | `tools/demo-seed/`、`W/` 修正 | 全部 | `A/journey-builder-integration/`、`WA/integration/journey-builder-integration/` | |
## 驗收條件對應（布告欄 10 條 → 單）
- 1 場景一：canvas-ui、branch-engine、integration＋uat；2 審核：lifecycle-api、review-ui；3 v1／v2：lifecycle-api、integration；4 `cart_id`：reentry-engine、integration；5 停止：lifecycle-api（在途列 `CANCELLED`、Mock 0 則）；6 軌跡與人數：node-stats-api、trace-branch-ui；7 遮罩：masking-default-api；8 uat 產物：本單；9 金鑰與鎖定：security-hardening-api；10 UX：兩張 fast＋ux-polish-web＋manager（#149）。designer（不在上表）：`journey-builder-ui` 進行中；#146、#150（`web/public/favicon.*` 歸 designer）、#180、#187 需 UI 規格；#149（README）歸 manager
## 自行決定
| 項目 | 決定 |
|---|---|
| 數值 | 金鑰下限 32 字、`CDP_LOGIN_IP_MAX_FAILURES` 建議 30、計數表上限 100,000、統計逾時 5 秒、節點 id ≤ 64 字 |
| 介面細節 | 訊息節點的管道／範本本版不給清單與預覽 API，手動輸入（`journeys.md` §4）；統計每版本全部 Enrollment、`entered` 以人計＋`visits`＋`out_ports`（§5）；`profile_name` 不明文回 `null`；條件（journey-contract-fix）：`event_filter` 的根是事件本身（`properties.<鍵>`）、條件節點的根是旅程進度（客戶屬性或 `trigger.properties.<鍵>`），照已合併的 `JourneyGraphValidator`；欄位型別不進契約（無欄位字典），比較依 `value` 的 JSON 型別、型別不合或缺值即不成立（`IS_NULL` 除外），避免引擎因資料髒拋錯；驗證錯誤 `field` 到條件的每一層（實作已如此） |
| 草稿寫入憑證（journey-contract-fix） | 收緊：草稿 `POST`／`PUT`／`DELETE`、`validate` 只收 session，管理金鑰 403（`auth.md` §2、`journeys.md` `Draft.updated_by` 改為必有）。理由：已合併的實作（`SecurityConfig` `editorSession`）與驗收（`RoleMatrixAcceptanceTest`）本來就如此；草稿紀錄要對得到人；金鑰的用途（腳本、demo-seed）已有 journey-engine §3.1；收緊不需 D-編號，放寬才屬 ④ |
| 刪草稿後的審核狀態（journey-contract-fix） | 收斂：「這份草稿的審核」= 草稿建立後送審的 `Review`（`journeys.md` §3），刪除或核准清除草稿後，新草稿不再帶出舊的 `REJECTED`。理由：刪草稿 = 放棄內容，帶出舊退回原因會誤導，且讓旅程誤入 `?review_status=REJECTED` 清單；同定義一併統一 `Draft.latest_review`（現排除 `APPROVED`）與 `review_status`（現取最新一筆）在金鑰紀錄上的差異。實作交 journey-draft-review-scope-api |
## 待甲方決定／需要調查
- 待決定 1～3 已定案，皆採建議案 A：1（#184 明文清單）→ D-083；2（#153 限速維度）→ D-084；3（場景一 `event_filter`）→ D-085。待決定 4（①）`WaitForEventNode.event_filter`：D-085 只含觸發節點，契約維持 `null`，但 `JourneyGraphValidator` 已接受、designer 規格（PR #217）已畫「等事件」篩選：A. 擴及等事件（建議：同一套驗證與判斷，`wake_event_filter` 欄已存在，branch-engine 多一處呼叫）／B. 只限觸發節點（validator 改為非 `null` 即錯、designer 拿掉等事件區塊）
- 需調查 1：已結案，可並用但不採用（結論與理由見 DESIGN-03 需調查 1）；需調查 2：正式環境反向代理的網段與是否覆寫 `X-Forwarded-For`（決定 `CDP_TRUSTED_PROXIES` 的值；代管維運提供）
## 風險
| 風險 | 對策 |
|---|---|
| 既有環境金鑰 < 32 字升版後起不來；新測試金鑰被 gitleaks 抓 | 發版說明列出、錯誤訊息指出變數；低熵值，仍被抓走 scan 通道 |
| 分散來源耗盡登入併發；lifecycle 與 branch-engine 之間分支版本跑不動 | 只回 429 不排隊、代理限速列 backlog；兩單間不發版（做法 #1） |
