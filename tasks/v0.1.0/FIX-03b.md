# 派工單 FIX-03b

- **類型**：fix
- **負責**：backend
- **目標**：發送管線穩健：relay 失敗退避與 mandatory 檢查、consumer 暫時性錯誤延遲重排、移除全域 missingQueuesFatal
- **背景**：做法與驗收要點見 docs/design/DESIGN-01.md「FIX-03」節後三列（relay、consumer、`missingQueuesFatal`），來源 PR #24 review；D-027 預設值 `OUTBOX_RELAY_RETRY_DELAY`=PT30S、`DELIVERY_REQUEUE_DELAY`=PT5S。
  補充（CHORE-02 review）：correlated confirm 以 `CorrelationData` future 在逾時內等結果，逾時、nack、`getReturned() != null` 都視同失敗；`RELAY_AFTER_CONFIRM` 注入點維持在 ack 確認之後；relay 遇未知 effect_type 的列也要 reschedule，不占批首。
  順序：qa 先改 `EngineTestSupport`、`SecurityTestSupport`（及 `acceptance/ingest` 的基底類）不再用空 `effectTopology`，worker 再刪 `RabbitTopology.listenerContainerCustomizer`。
- **設計依據**：docs/design/DESIGN-01.md 做法 #4（含例外分流表）、「FIX-03」節；docs/spec/05-delivery-pipeline.md；docs/spec/07-database-schema.md §10
- **相依**：無（FIX-03a 已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/delivery/`
  - `api/src/test/java/com/cdp/delivery/`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`（只限 `cdp.relay.retry-delay`、`cdp.delivery.requeue-delay`、`spring.rabbitmq.{publisher-confirm-type,publisher-returns,template.mandatory}`）
  - `.env.example`（只新增 `OUTBOX_RELAY_RETRY_DELAY`=PT30S、`DELIVERY_REQUEUE_DELAY`=PT5S）
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、migration、`J/persistence/` 全部、其他套件；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/delivery/`
- **驗收條件**：
  - [ ] broker 不可用或 confirm 逾時：一批只等一次逾時即結束，失敗列 `available_at` 延後 `OUTBOX_RELAY_RETRY_DELAY`；exchange 無 binding（returned）或 nack 時留 `PENDING`、不標 `PUBLISHED`
  - [ ] consumer 暫時性 DB 例外重排前等 `DELIVERY_REQUEUE_DELAY`，斷線期間不空轉；恢復後恰好一則；`CannotCreateTransactionException` 有驗收
  - [ ] `channel_type` 取自 adapter；`T/delivery/` 有單元測試；`RabbitTopology` 不再有全域 `missingQueuesFatal=false`
  - [ ] 既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-03b.json`

## 執行資訊（task.py 產生）

- Issue：#33
- 分支：`fix/fix-03b`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-03b`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
