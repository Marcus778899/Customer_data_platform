# 派工單 relay-loop-rewrite

- **類型**：feat
- **負責**：backend
- **目標**：依 DESIGN-03「relay 與迴圈」改寫 outbox relay（搶列→交易外整批送出→小交易條件式回寫）、迴圈小交易與滿批續做、broker 逾時與關機設定
- **背景**：即 DESIGN-03 派工表的 BE-05（驗收為 QA-02）。外部 review #72、D-036～D-038；新設定值採團隊建議值（D-047），變數已在 `.env.example`。不需 migration。驗收測試由 qa 在本分支先寫好，不可改；目錄名含 `-` 不是合法 Java 套件名，測試檔的 package 宣告用 `com.cdp.acceptance.relayloop`。
- **設計依據**：`docs/design/DESIGN-03.md`「relay 與迴圈」「設定」；`docs/spec/07-database-schema.md` §12「relay」與 `effect_outbox` 表；`docs/contracts/journey-engine.md`
- **相依**：tenant-id-drop（已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/delivery/`
  - `api/src/main/java/com/cdp/engine/runtime/AdvanceLoop.java`
  - `api/src/main/java/com/cdp/ingest/EventDispatcher.java`
  - `api/src/main/java/com/cdp/persistence/JdbcOutboxStore.java`
  - `api/src/main/java/com/cdp/common/fault/`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`
  - `api/src/test/java/com/cdp/` 中對應上述類別的單元測試（`acceptance/` 除外）
- **禁區**：`api/src/test/java/com/cdp/acceptance/`；migration；對外 API 行為與回應格式；`web/`、`docs/`、`tools/`、`.env.example`；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/relay-loop-rewrite/`
- **驗收條件**：
  - [ ] `OutboxStore` 改為 `claimDue`／`markPublished`／`reschedule`（回傳是否生效）、`cancelPendingByEnrollment`／`ByJourney`，移除 `lockDue`；relay 交易外整批 `convertAndSend`＋`CorrelationData`，一起等到 `OUTBOX_RELAY_CONFIRM_TIMEOUT`，再一個小交易 `batchUpdate` 條件式回寫；`FaultPoint` 加 `RELAY_BEFORE_WRITEBACK`
  - [ ] `AdvanceLoop` 每筆一個交易、本輪失敗者略過；`EventDispatcher` 每個事件一個交易；三個迴圈滿批續做到 `CDP_LOOP_MAX_DRAIN`
  - [ ] `spring.task.scheduling.pool.size`＝`CDP_SCHEDULER_POOL_SIZE`（啟動驗證 ≥ 迴圈數）；`RABBITMQ_CONNECTION_TIMEOUT`、`DELIVERY_SHUTDOWN_TIMEOUT` 接上；lease ≤ confirm 逾時拒絕啟動；consumer 遇 outbox 列 `CANCELLED` → `delivery_task.CANCELLED`
  - [ ] 驗收情境：兩處中斷恰好一則、lease 過期重發恰好一則、遲到 confirm 不覆寫（`publish_count` = 1）、relay 卡住時計時器照常推進、3 倍批量一個 tick 內清空
  - [ ] 驗收測試全綠；既有測試（含 `acceptance/delivery/`）全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/relay-loop-rewrite.json`

## 執行資訊（task.py 產生）

- Issue：#94
- 分支：`feat/relay-loop-rewrite`（從 develop 開出）
- 工作目錄：`.worktrees\relay-loop-rewrite`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
