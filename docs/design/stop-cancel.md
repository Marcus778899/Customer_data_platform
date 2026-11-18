# 設計：停止與中途結束時被取消訊息的發送紀錄（stop-cancel-design）
<!-- uat F-29（docs/uat/v0.3.0.md 第四輪）。J = api/src/main/java/com/cdp、A = api/src/test/java/com/cdp/acceptance、W = web/src、WA = web/tests/acceptance -->
- 契約：`journey-engine.md` §2 `CancelReason`、§4.1 `Delivery.cancel_reason`／`attempted_at`；`journeys.md` §3.5 效果；語意正本 spec 05 §5「落地」、spec 07 §12「V10」與「relay」取消列
- 現況：取消有三個入口，都只把 `effect_outbox` 改 `CANCELLED`：`JourneyStopService.stop` → `cancelPendingByJourney`（停止當下，含已走完的 Enrollment，F-29 的情境）、`JourneyStopSweeper.abort` 與 `DefaultEnrollmentAdvancer.endBySettings` → `cancelPendingByEnrollment`。relay 不撈 `CANCELLED`，`delivery_task` 沒有列，`JdbcEnrollmentQueryStore.deliveries` 只讀 `delivery_task`，所以 `delivery_history` 為空。只有已推送的那則由 `DeliveryConsumer` 寫 `delivery_task.CANCELLED`

## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 資料來源 = `delivery_task`：`OutboxStore.cancelPendingByEnrollment(enrollmentId, reason, at)`／`cancelPendingByJourney(journeyId, reason, at)` 改成一條 SQL：`WITH c AS (UPDATE effect_outbox SET status='CANCELLED' WHERE … AND status='PENDING' RETURNING …) INSERT INTO delivery_task (…) SELECT … FROM c WHERE effect_type='SendMessage' ON CONFLICT (idempotency_key) DO NOTHING`，回傳取消筆數 | 查詢 API、分頁、排序都不用改；取消與紀錄同一敘述，不會只做一半 |
| 2 | 新列欄位：路由欄位取 outbox 同名欄位，`template_id`／`channel_id` 取 `payload`（同 spec 07 §10）；`channel_type` = `ChannelAdapter.channelType()`（`JdbcOutboxStore` 注入）；`status`=`CANCELLED`、`cancel_reason`=呼叫端給的值、`attempted_at`=`created_at`=`updated_at`=呼叫端的 `Clock` 時間；`message_type`、`skip_reason`、`delivery_id` = `NULL` | 與 consumer 寫的列同形；時間取注入的 `Clock`（契約 §1） |
| 3 | 呼叫端：`JourneyStopService.stop`、`JourneyStopSweeper.abort` 帶 `JOURNEY_STOPPED`；`DefaultEnrollmentAdvancer.endBySettings` 帶 `ENROLLMENT_ENDED` | 前端分得出「停止」與「客戶中途結束」 |
| 4 | `DeliveryConsumer`：KillSwitch 擋下 → `DeliveryTaskStore.markCancelled(key, JOURNEY_STOPPED, now)`（新方法，取代現在組整個 `DeliveryTask` 的 `update`）；讀到 outbox `CANCELLED` 但沒有 `delivery_task` 列（V10 前取消的舊資料）→ `markCancelled(key, null, now)` | 新取消的列已由 #1 寫好，consumer 走「已處理過」分支直接 ack |
| 5 | V10：`delivery_task` 加 `cancel_reason TEXT NULL`＋CHECK（spec 07 §12「V10」）；expand，不回填 | 舊版程式不寫此欄，可並存；down 只刪欄 |
| 6 | 不回填 V10 前已 `CANCELLED`、沒有 `delivery_task` 的 outbox 列 | 停止與 goal／global_exit 都是 v0.3.0 新功能，未發版，正式環境沒有這種列；開發資料不值得做資料遷移（④） |
| 7 | `enrollment_record.delivery_history`（節點決策快取，spec 07 §9 第 9 條）不追加取消紀錄 | 與 consumer 現在的 `CANCELLED` 分支一致；它只給節點判斷頻率用 |
| 8 | 併發：consumer 已為同一 key 插入 `PENDING` 列（relay 推送後、`markPublished` 提交前）時，#1 的 `ON CONFLICT` 等它提交後不動；結果以 `delivery_task` 最終狀態為準（可能是 `SENT`） | 已在途的那則屬既有競態（spec 05 §5 落地）；取消不覆寫已送出的事實 |

## 業務規則與術語：`rules.md`「停止」加 1 條、新增「中途結束」1 條；`README.md` 核心規則「停止」一行補發送紀錄；`glossary.md` 加「取消原因」
## 設定（寫進 .env.example，這裡不寫值）：無

## 威脅與對策
| 資產 | 威脅 | 對策 |
|---|---|---|
| 發送紀錄的可信度 | 取消紀錄被誤當「已送出」或蓋掉已送出的紀錄 | #8 `ON CONFLICT DO NOTHING`；CHECK 限 `cancel_reason` 只出現在 `CANCELLED`；端點、授權、遮罩不變（`Delivery` 不含個資，§4.4） |

## 受影響的既有驗收測試：無
已 grep `A/`、`WA/` 的 `CANCELLED`、`delivery_task`、`deliveries`、`delivery_history`。`A/journey-lifecycle-service/JourneyStopAcceptanceTest`、`A/journey-builder-integration/CartIdAndStopAcceptanceTest`、`A/journey-branch-engine/TerminalAndSettingsAcceptanceTest` 只驗 outbox 狀態與 `mock_channel_message` 筆數；`A/delivery/DeliveryPipelineAcceptanceTest` KillSwitch 案例驗 `delivery_task.status`＝`CANCELLED`、`delivery_history` 長度 0，皆不變；`A/query/EnrollmentQueryAcceptanceTest` 以明列欄位灌 `delivery_task`，新欄可為 null。單元測試 `JourneyStopSweeperTest`、`JourneyStopServiceTest` 因簽名改變由實作單一併改

## 檔案分工
| 檔案 | 負責 |
|---|---|
| `api/src/main/resources/db/migration/V10__delivery_task_cancel_reason.sql`（新）、`J/delivery/CancelReason`（新）、`OutboxStore`、`DeliveryTaskStore`、`DeliveryConsumer`、`J/persistence/JdbcOutboxStore`、`JdbcDeliveryTaskStore`、`JdbcEnrollmentQueryStore`、`J/query/DeliveryEntry`、`J/journey/JourneyStopService`、`J/engine/runtime/JourneyStopSweeper`、`DefaultEnrollmentAdvancer`、`EngineJourneyRuntime`（做法見 spec 02 §6 落地「冷卻期上限」）及其單元測試 | stop-cancel-record-api（backend） |
| `W/pages/trace/`、`W/pages/TracePage.tsx`、`W/plain/`，追加 `W/api/types.ts`（`Delivery.cancel_reason` 設選填） | trace-cancelled-fix（frontend，已開單） |

## 派工順序
| task_id | 角色 | 內容 | 驗收目錄 | 相依／平行 |
|---|---|---|---|---|
| stop-cancel-record-api | backend | 做法 #1～#5；spec 02 §6「冷卻期上限」夾值；驗收另含 spec 02 §5 步驟 3（現行實作已符合，預期不改程式） | `A/stop-cancel-record-api/` | 無；與 trace-cancelled-fix 平行（前端對契約 mock） |
| trace-cancelled-fix | frontend | 依 `cancel_reason` 顯示取消原因（manager 追加 `W/api/types.ts` 與驗收範圍） | `WA/trace-cancelled-fix/` | 平行 |

- 不另開整合單：後端驗收以真 API 驗 `delivery_history`；合併後由 uat 依 F-29 重現步驟複測

## 自行決定（可逆、在範圍內；甲方核可設計時一起看，要改再說）
| 項目 | 決定 |
|---|---|
| 取消紀錄的時間與原因 | `attempted_at` = 取消時間；`cancel_reason` 兩值 `JOURNEY_STOPPED`／`ENROLLMENT_ENDED`，舊資料可為 `null` |
| 布告欄 spec 待辦（文件更正，收緊或對齊已合併實作） | DESIGN-05 #3 兩句改成實作（`:name`、`ifneq origin`）；spec 07「V9」讀取列；spec 02 §3＋DESIGN-03 #8 終態取消限「中途結束」；spec 02 §5 白名單讀法、`_total_count`、步驟 3 走派發重試到 `EXHAUSTED`；spec 02 §6 冷卻 cutoff 夾到 1970-01-01、advisory lock 取鎖順序 |

## 待甲方決定／需要調查：無

## 風險
| 風險 | 對策 |
|---|---|
| 停止大旅程時 `cancelPendingByJourney` 同交易插入大量 `delivery_task` | 列數與現在的 `UPDATE` 相同；超量時另議分批 |
| #1 等 consumer 的列鎖、consumer 送出時再鎖 `enrollment_record`（`appendDeliveryHistory`），與已鎖該 Enrollment 的 sweeper／advancer 可能死結 | PostgreSQL 偵測後中止一方：consumer 屬暫時性錯誤會重排，sweeper／advancer 下輪重試；只在推送與回寫之間的窄窗發生 |
