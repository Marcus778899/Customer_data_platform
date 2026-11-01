TEST_WRONG 526ab10

驗收測試 lock 檢查：跟 lock（commit 111f981）時一樣，沒被改過

依據：`docs/spec/07-database-schema.md` §12「relay」（搶列把 `available_at` 設為 `lease_until = now + OUTBOX_RELAY_LEASE`；只搶 `available_at <= now`；回寫失敗或沒回寫前列都不會再到期）、`docs/design/DESIGN-03.md`「relay 與迴圈」驗收列（「沿用兩處中斷恰好一則」＋「lease 過期重發……快轉超過 lease」）、派工單驗收條件 4、5。

## 判定

| 測試名稱 | 契約條文 | 判定與理由 |
|---|---|---|
| `acceptance/delivery/DeliveryPipelineAcceptanceTest::relayInterruptedBeforePublish_thenRerun_sendsExactlyOnce` | spec 07 §12「relay」搶列列 | 測試錯。第一輪搶列已把 `available_at` 設為 lease_until（可控 Clock 未前進），中斷後沒有回寫，下一輪 `relay.runOnce()` 在 lease 內依契約搶不到該列；測試要求立刻重推，與契約矛盾。同一情境在 `relay-loop-rewrite/RelayRewriteAcceptanceTest::interruptBeforePublish_thenRestart_exactlyOnce` 已以「快轉超過 lease 後重跑」的正確寫法存在 |
| `acceptance/delivery/DeliveryPipelineAcceptanceTest::relayInterruptedAfterConfirm_thenRestart_republishes_butMockSendsExactlyOnce` | spec 07 §12「relay」搶列列、回寫列；DESIGN-03 驗收「lease 過期重發」 | 測試錯。理由同上；且本單 `RelayRewriteAcceptanceTest::leaseExpiredResend_isAbsorbedByConsumer_exactlyOne_publishCountOne` 明確要求「lease 內另一輪不得重搶」，兩個測試不可能同時通過 |
| `acceptance/relay-loop-rewrite/RelayStuckTimersAcceptanceTest.java`（檔案層級，lint） | pom.xml spotless `importOrder`（`java,javax,jakarta,org,com,`）；派工單驗證指令 `make check` | 測試檔錯。lock 版本第 16 行 `import org.springframework.beans.factory.annotation.Autowired;` 放在 `org.testcontainers` 之後另成一組，`./mvnw -o spotless:check` 實跑：「1 needs changes ... src\test\java\com\cdp\acceptance\relay-loop-rewrite\RelayStuckTimersAcceptanceTest.java」，`make check` 的 lint 必失敗，與實作無關 |

## 測試應該怎麼改（qa）

1. `DeliveryPipelineAcceptanceTest`：
   - `@SpringBootTest(properties = …)` 加 `"cdp.relay.lease=PT10S"`，lease 由測試自己釘住，不依賴 worker 負責的 `application-test.yml`（其 `confirm-timeout` PT5S < PT10S，啟動驗證可過）。
   - `relayInterruptedBeforePublish_thenRerun_sendsExactlyOnce`：`faults.disarm(...)` 之後、第一次重跑 `relay.runOnce()` 之前加 `clock.advance(Duration.ofSeconds(11))`（超過 lease）；其餘斷言不變。
   - `relayInterruptedAfterConfirm_thenRestart_republishes_butMockSendsExactlyOnce`：`finally` 之後、「重啟」的 `relay.runOnce()` 之前加同樣的 `clock.advance(...)`；其餘斷言不變。
   - 也可改成刪除這兩個測試（情境已由 `RelayRewriteAcceptanceTest` 的 `interruptBeforePublish_…`、`interruptAfterConfirm_…` 涵蓋），二擇一，不可保留現狀。
2. `RelayStuckTimersAcceptanceTest.java`：把 `import org.springframework.beans.factory.annotation.Autowired;` 移進上方 `org.` 那組（`org.springframework.boot…` 之前），刪掉多出的空白組；改完以 `./mvnw spotless:check` 驗證。
3. 改完後 lock 要隨這次改動重設，實作端不需修改。

## qa 證據

本次為 worker disputes 觸發的仲裁，沒有 qa FAIL 項目可判。

## 契約問題（非決策分級四類，不需甲方）

- DESIGN-03 派工表把 BE-05＋QA-02 的驗收目錄寫成 `delivery/`，派工單改用 `relay-loop-rewrite/`；設計寫「沿用兩處中斷」，但 qa 沒同批改寫 `delivery/` 中依舊語意（無 lease）寫的兩個測試。之後改動既有語意的單，qa 須同批改寫受影響的舊驗收。
- 另：worker 回報 spotless:apply 曾改到 qa 檔的工作副本，現在 worktree `git status` 乾淨，已還原。
