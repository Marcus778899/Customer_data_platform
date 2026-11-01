PASS 921c51d

# QA relay-loop-rewrite（B 驗證，HEAD 921c51d）

## 實際執行
1. `docker compose --env-file .env -f .worktrees/relay-loop-rewrite/compose.yaml run --rm test`
   - 結果：`Tests run: 204, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
   - 驗收測試：relayloop.RelayRewriteAcceptanceTest 7、RelayStuckTimersAcceptanceTest 1、RelayStartupValidationAcceptanceTest 5，全綠
   - 既有 acceptance/delivery：DeliveryPipelineAcceptanceTest 12、DeliveryResilienceAcceptanceTest 5，全綠；其餘 acceptance（ingest、engine、security、query、schema、common）全綠
   - 輸出中的 "APPLICATION FAILED TO START" 4 次來自啟動驗證的負向測試（DispatchPropertiesStartupAcceptanceTest 等），該些測試通過。
2. `cd api && ./mvnw -o -q spotless:check` -> exit 0
3. `npm run lint -w web`（eslint . && tsc --noEmit）-> exit 0（前端測試沒動，未跑）

## 驗收測試 lock
- `git diff --stat f6a908a HEAD -- api/src/test/java/com/cdp/acceptance/` 無輸出：自仲裁後 lock（f6a908a）起沒被改。
- worker 分支對 acceptance 的變更只有 qa 的 111f981、f6a908a。

## 改動範圍
`git diff --name-only 4aa0430 HEAD` 全在負責檔案內：delivery/、AdvanceLoop、EventDispatcher、JdbcOutboxStore、common/fault/FaultPoint、application.yml、application-test.yml、對應單元測試（DeliveryConsumerTest、EventDispatcherBackoffTest、EventDispatcherUnparsableRowTest），其餘為 qa 的驗收測試。沒有 migration、web/、docs/、.env.example 變更。

## 驗收條件逐條
1. OutboxStore：有 claimDue／markPublished／reschedule（boolean）／cancelPendingByEnrollment／ByJourney（另有 markPublishedAll／rescheduleAll 批次版），無 lockDue（OutboxStore 內已移除）；relay 交易外 convertAndSend＋CorrelationData、統一等 confirm、小交易 batchUpdate；FaultPoint 有 RELAY_BEFORE_WRITEBACK。通過（行為由 RelayRewriteAcceptanceTest 驗）。
2. AdvanceLoop、EventDispatcher 每筆一交易、失敗者略過；滿批續做至 CDP_LOOP_MAX_DRAIN（3 倍批量一個 tick 清空之測試通過）。通過。
3. spring.task.scheduling.pool.size=${CDP_SCHEDULER_POOL_SIZE}、connection-timeout=${RABBITMQ_CONNECTION_TIMEOUT}、shutdown-timeout=${DELIVERY_SHUTDOWN_TIMEOUT}；LoopStartupValidator 驗 pool >= 迴圈數、lease > confirm 逾時（<= 即拒絕啟動）；consumer 遇 outbox CANCELLED 寫 delivery_task.CANCELLED。測試 RelayStartupValidationAcceptanceTest 通過。通過。
4. 驗收情境（兩處中斷恰好一則、lease 過期重發、遲到 confirm 不覆寫 publish_count=1、relay 卡住計時器照常、3 倍批量一 tick 清空）：RelayRewriteAcceptanceTest／RelayStuckTimersAcceptanceTest 全綠。通過。
5. 驗收與既有測試全綠：204/204。通過。

## 設定值
application.yml 新增項目全為 `${VAR}`，無預設值、無寫死；新變數 OUTBOX_RELAY_CONFIRM_TIMEOUT=PT5S、OUTBOX_RELAY_LEASE=PT30S、RABBITMQ_CONNECTION_TIMEOUT=PT10S、DELIVERY_SHUTDOWN_TIMEOUT=PT30S、CDP_SCHEDULER_POOL_SIZE=4、CDP_LOOP_MAX_DRAIN=PT10S 都在 .env.example（D-047 建議值）。main 程式未新增寫死 port／網址／token／逾時（EventDispatcher 的 LATE_THRESHOLD 24h 為既有常數）。application-test.yml 的值是測試假值，屬測試設定。

## open_questions 的偏離是否違反驗收條件
- lockDue（StateStore／EventStore）無排除清單、改取 failed+1 列略過：驗收條件的 lockDue 是指 OutboxStore，已移除；「本輪失敗者略過」行為達成，不違反。介面是否加排除參數由 review 判斷（副作用：失敗列在該小交易內被鎖住）。
- LoopProperties 綁 CDP_LOOP_MAX_DRAIN：變數存在且生效，不違反；歸屬由 review 判斷。
- 保留 rabbit.invoke（同一 channel 送出並等 confirm，仍在 DB 交易外）：驗收條件寫「交易外整批 convertAndSend＋CorrelationData，一起等 confirm」，invoke 不影響此行為，且 DeliveryResilience 全綠；不違反條件，與設計文字不同，由 review 判斷。
- 迴圈數常數 3：驗收「啟動驗證 ≥ 迴圈數」達成；v0.3.0 加 JourneyStopSweeper 須改為 4（風險記錄，不是本單 FAIL）。

## 備註
- 編譯警告：OutboxRelay 使用 CorrelationData.Confirm.isAck()／getReason()，已標 deprecated for removal（非失敗）。

## FAIL 項目
無（0）。
