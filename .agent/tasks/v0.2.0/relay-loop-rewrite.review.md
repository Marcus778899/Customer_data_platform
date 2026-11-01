APPROVE 921c51d

# Review PR #99（relay-loop-rewrite）

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 改動範圍（`gh pr diff 99`） | main 程式全在負責檔案內（delivery/、AdvanceLoop、EventDispatcher、JdbcOutboxStore、common/fault/、兩個 yml）；acceptance/ 的變更只有 qa 的 111f981、f6a908a（仲裁後 lock）；無 migration、web/、docs/、.env.example |
| spec 07 §12「relay」 | 搶列：單一 `UPDATE … WHERE key IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`，`available_at = lease_until`（截到微秒，fencing 比對正確）；發送在 DB 交易外；回寫一個小交易 `batchUpdate`，條件 `status='PENDING' AND available_at = lease`；失敗 `available_at = now + RETRY_DELAY`；影響 0 列丟棄並記 log；取消 `PENDING → CANCELLED`；consumer 讀到 outbox `CANCELLED` 寫 `delivery_task.CANCELLED` |
| DESIGN-03「relay 與迴圈」「設定」 | `OutboxStore` 介面、`RELAY_BEFORE_WRITEBACK`、每筆／每事件一個交易、三迴圈滿批續做、pool size、connection／shutdown timeout、lease > confirm 啟動驗證都有；六個變數全為 `${VAR}`，無寫死值；舊的 `CONFIRM_TIMEOUT_MILLIS = 5000` 常數已移除 |
| 交易邊界 | `DefaultEnrollmentAdvancer.advance`、`EngineJourneyRuntime.start` 仍為 NESTED，在每筆的 `TransactionTemplate` 內失敗只回滾 savepoint、外層正常提交，不會 `UnexpectedRollbackException` |
| 失敗與中斷路徑 | `RELAY_BEFORE_PUBLISH`／`RELAY_BEFORE_WRITEBACK` 中斷：不回寫、維持 lease，到期重搶；`convertAndSend` 例外後同批其餘列直接列失敗（不逐列等逾時）；`invoke` 本身例外：已送出的列保留 lease；`clean=false` 即停止續做，broker 異常時不空轉 |
| 測試 | CI `Check` pass；qa 證據 204/204（含 `acceptance/relay-loop-rewrite/` 13 個、`acceptance/delivery/` 17 個）；驗收情境對應派工單驗收條件 4 |

## worker 偏離判定（都可接受，不阻擋）

| # | 偏離 | 判定 | 理由 | 後續 |
|---|---|---|---|---|
| 1 | 保留 `rabbit.invoke`，整批送出與等 confirm 包在一個 invoke 內 | 接受 | 設計要的是「不在 DB 交易內、整批送出一起等 confirm」，這兩點都做到；invoke 只決定共用同一 channel，不是 DB 交易。每則各取 channel 會在 broker 暫停時卡新 channel 開啟，`DeliveryResilienceAcceptanceTest` 全綠佐證 | 改設計文件（下方 A） |
| 2 | `StateStore.lockDue`／`EventStore.lockUndispatched` 無排除清單，改取 `failed+1` 列略過失敗者 | 接受 | 正確性沒問題（排序固定，F+1 列至少含一筆未失敗者）。`EventDispatcher` 失敗時 `scheduleRetry` 把 `next_dispatch_at` 往後移，失敗列下一筆就查不到，實際不會累積。`AdvanceLoop` 失敗列 `next_wake_at` 不變、排在最前面：同一 tick 有 F 筆失敗時每個交易要讀並鎖 F+1 列，大量系統性失敗時會變成平方成本（受 `CDP_LOOP_MAX_DRAIN` 限制，比改版前「整批失敗卡住」好）。原因是派工單沒把 `StateStore`／`JdbcStateStore` 列進負責檔案 | 補單 B1；改設計文件（A） |
| 3 | `CDP_LOOP_MAX_DRAIN` 綁在新的 `com.cdp.delivery.LoopProperties`（`cdp.loop.max-drain`） | 接受 | 三個迴圈共用的設定獨立成一個 record，比塞進 `EngineProperties`（engine 專用）合理；變數名稱不變。缺點：`engine.runtime`、`ingest` 因此依賴 `com.cdp.delivery` 套件，套件位置不理想（`ArchitectureTest` 有過） | 補單 B2（移到 `com.cdp.common`，可併入下一張碰到這些檔案的單）；改設計文件（A） |
| 4 | `LoopStartupValidator.LOOP_COUNT = 3` | 接受 | 本版確實三個迴圈；`.env.example` 預設 4 本來就留了第四條 | JourneyStopSweeper 那張單須把常數改 4 並補驗收（B3）；改設計文件（A） |
| 5 | `CorrelationData.Confirm.isAck()`／`getReason()` deprecated for removal | 接受 | 目前不影響行為 | 補單 B4：改用替代 API（`ack()`／`reason()`，以所用 Spring AMQP 版本的 Javadoc 為準） |

## 非阻擋觀察

- `OutboxRelay.sendAndConfirm`：confirm 截止時間在全部送出「之後」才起算，總耗時 = 送出時間 + `confirmTimeout`；broker 觸發 connection.blocked 時 `convertAndSend` 可能長時間阻塞，回寫會因 lease 過期而 0 列、由下一輪重發。fencing 與 consumer 冪等保住正確性，「relay 卡住時計時器照常推進」也由獨立執行緒池保住；記入 QA-06 壓測觀察項即可，不需改。
- `writeBack` 把 `Statement.SUCCESS_NO_INFO` 當成功只影響回傳筆數與 log，不影響 DB 狀態。
- `LoopStartupValidator`、`DeliveryListenerConfig` 沒有 worker 自己的單元測試，靠驗收測試覆蓋；CI 覆蓋率閘門已過。

## A. 需要改的設計文件（manager 派 architect 改）

| 檔案 | 位置 | 改成 |
|---|---|---|
| `docs/design/DESIGN-03.md` | 「relay 與迴圈」`OutboxRelay.runOnce` 列 | 「拿掉 `rabbit.invoke`」改為「整批送出與等 confirm 包在一個 `rabbit.invoke`（同一 channel，仍在 DB 交易外；每則各取 channel 在 broker 暫停時會卡新 channel 開啟）」 |
| `docs/design/DESIGN-03.md` | 「執行緒與小交易」列 | 寫明 v0.2.0 現況：`lockDue(now, 失敗數+1)` 略過本輪失敗者；排除參數版本見補單 B1；`CDP_LOOP_MAX_DRAIN` 綁 `cdp.loop.max-drain`（`LoopProperties`） |
| `docs/design/DESIGN-03.md` | 做法 #7（JourneyStopSweeper，第四個迴圈） | 加「`LoopStartupValidator.LOOP_COUNT` 改 4」 |
| `docs/design/DESIGN-03.md` | 派工表第 2 列驗收目錄 | `delivery/` 改 `relay-loop-rewrite/`（仲裁已提） |

## B. 建議補單（非阻擋）

| # | 內容 | 負責檔案 |
|---|---|---|
| B1 | `StateStore.lockDue(now, limit, Set<String> exclude)`（`enrollment_id <> ALL(:exclude)`），`AdvanceLoop` 改用；`EventStore` 可不動（已有退避） | `engine/StateStore.java`、`persistence/JdbcStateStore.java`、`AdvanceLoop.java` |
| B2 | `LoopProperties` 移到 `com.cdp.common`（或 `com.cdp.common.loop`） | `delivery/LoopProperties.java` 與三個迴圈、`LoopStartupValidator` |
| B3 | 併入 JourneyStopSweeper 單：`LOOP_COUNT` 改 4、啟動驗證驗收補 pool=3 拒絕啟動 | `delivery/LoopStartupValidator.java` |
| B4 | 換掉 `Confirm.isAck()`／`getReason()` deprecated API | `delivery/relay/OutboxRelay.java` |
