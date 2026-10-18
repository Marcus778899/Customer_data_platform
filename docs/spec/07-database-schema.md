# 07 — 資料表總覽（實作層）

> **本檔是彙整，不是正本。** 每個欄位的語意正本仍在 `01`~`06`，本檔只做兩件事：
> 1. 把散落各處的表定義收在一頁，讓寫 migration 時不必翻五份規格
> 2. 記錄「契約 → DDL」翻譯過程中做的落地決策（型別對照、主鍵、索引、約束）
>
> 契約語意有疑義時以 `01`~`06` 為準；DDL 形狀有疑義時以本檔為準。

---

## 1. 全表清單

| 表 | 承載的契約 | 語意正本 | Flyway | 狀態 |
|---|---|---|---|---|
| `journey_definition` | 旅程定義 | `03` §1, §2, §3 | V1 | Phase 0 |
| `enrollment_record` | `EnrollmentState` 的實作層 | `02` §9 | V1 | Phase 0 |
| `node_trace` | `EnrollmentTrace` | `02` §8 | V1 | Phase 0 |
| `event` | 事件契約（最小版） | `01` §3 | V1（V2 加 `dispatched_at`） | Phase 0 |
| `effect_outbox` | Effect 交易外寄箱 | `04` §5、本檔 §10 | V2 | v0.1.0 |
| `delivery_task` | 發送紀錄完整版（任務級狀態，consumer 冪等依據） | `05` §6、本檔 §10 | V2 | v0.1.0（v0.3.0 起擴充 `05` §6 的完整狀態） |
| `mock_channel_message` | Mock 通道已送出的訊息（驗收計數） | 本檔 §10 | V2 | v0.1.0 |
| `field_definition` | 資料字典 | `06` §1 | — | **未定義**，Phase 3 |
| `event_definition` | 事件字典 | `06` §1 | — | **未定義**，Phase 3 |

> 後四張目前只有行為描述、沒有欄位定義。輪到它們時要**先補規格再寫 migration**，不要在 migration 裡即興發揮。

### ⚠️ 客戶資料不在這張清單裡

上表**沒有 `profile` 表**，這不是遺漏。裁決 3 把 `ProfileStore` / `SegmentEngine` 歸為「資料抽象」——**旅程引擎不擁有客戶資料，只透過介面問**。

| 層面 | 現況 |
|---|---|
| 怎麼**描述**受眾條件 | ✅ `06` 條件樹與資料字典 |
| 引擎怎麼**問** | ✅ `04` §8 的 `ProfileStore` / `SegmentEngine` 介面 |
| 客戶資料怎麼**存**、受眾包怎麼**算**、身分怎麼**解析** | ❌ 未定義，Phase 3 |

因此 `enrollment_record.profile_id` 只是個**外部識別碼**，指向一個本系統裡尚不存在的實體。Phase 0/1 塞任意 ULID 都能跑通整條旅程——這正是抽象有效的證據。

`AudienceSourceNode` 同理：`03` §5 有節點定義、schema 驗得過，但計劃書 Phase 3 才「接上引擎」。現在它畫得出來、推不動。

---

## 2. 共通約定

### 型別對照

契約層的 Java 型別翻成 Postgres 的固定對應，**四張表一致，不逐表重新決定**：

| 契約層 | Postgres | 理由 |
|---|---|---|
| `String`（ULID） | `TEXT` | Postgres 不帶長度的 `VARCHAR` 與 `TEXT` 完全等價；統一用 `TEXT`，避免看起來像有差別 |
| `Instant` | `TIMESTAMPTZ` | README 共通約定：儲存一律 UTC |
| `enum`（`EnrollmentStatus` 等） | `TEXT` | 不用 Postgres `ENUM` 型別——增減值要 `ALTER TYPE`，跨 migration 很痛。約束靠 `CHECK` 或應用層 |
| `Map` / `List` / 巢狀物件 | `JSONB` | 需要索引與運算子支援，不用 `JSON` |
| `int` | `INT` | |
| `long`（`seq`） | `BIGINT` | |

### 每張表都要有

| 欄位 | 規則 |
|---|---|
| `tenant_id TEXT NOT NULL` | README 共通約定「所有實體必帶」。**RLS 排到 Phase 1b，但欄位現在就要有**——事後加欄位要回填所有歷史資料 |

### RLS 的現況

`02` §9 的 DDL 結尾有 `ALTER TABLE enrollment ENABLE ROW LEVEL SECURITY`，**Phase 0 先不要下這行**。

開了 RLS 卻沒有對應的 `POLICY` 時，Postgres 的預設行為是**全部拒絕**——所有查詢回空集合，且不報錯。`ENABLE` 與 policy 必須同一個 migration 一起上，排在 Phase 1b。

`journey_engine` role 與 RLS、policy 同一個 migration 於 v0.2.0 上線（`docs/design/INIT-01.md` R2）。

### 命名

- 表名、欄位名：`snake_case`
- 唯一索引：`uq_<表>_<用途>`
- 一般索引：`ix_<表>_<用途>`

---

## 3. `journey_definition`

正本：`03` §1（版本化）、§2（頂層結構）、§3（settings）

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `journey_id` | TEXT | ✅ | ULID |
| `version` | INT | ✅ | 單調遞增，不重用 |
| `tenant_id` | TEXT | ✅ | |
| `name` | TEXT | ✅ | |
| `description` | TEXT | | |
| `status` | TEXT | ✅ | `DRAFT` / `TESTING` / `PENDING_APPROVAL` / `ACTIVE` / `PAUSED` / `ARCHIVED` / `ABORTED` |
| `settings` | JSONB | ✅ | 含 `priority`(G6)、`reentry`(G3)、`global_exit`、`goal`、`on_pause`、`budget` |
| `entry_node` | TEXT | ✅ | |
| `nodes` | JSONB | ✅ | 整包存。已發布不可改，正規化無收益；且 `journey-definition.schema.json` 已能驗證結構 |
| `created_at` | TIMESTAMPTZ | ✅ | ⚠️ 契約沒列，見 §7 決策 3 |
| `published_at` | TIMESTAMPTZ | | 同上。`DRAFT` 時為 null |

### 主鍵

```
PRIMARY KEY (journey_id, version)
```

**不是 `journey_id` 單欄。** `03` §1 的「已發布版本永不修改，改動產生新版本」，加上 `02` §2 的「`journeyVersion` 建立時綁定、終生不變」，合起來要求同一 `journey_id` 的多個版本**並存**：五萬人卡在 v1 的等待節點時，v1 的定義必須還查得到。

### 索引：不加

一個租戶幾十到幾百條旅程，加上版本累積，全表仍在**萬級**。這個大小 Postgres planner 通常主動忽略索引走 Seq Scan——讀幾百頁比走索引再回表快。加了只是多一份要維護的東西。

Partition 同理，不需要。那是給 `event` 表的（億級、有時間維度可切）。

> **但 PK 不受這個理由影響。** `(journey_id, version)` 不是效能設施，是「v1 與 v2 必須並存且不重複」的正確性約束——表只有 3 列時一樣必要。
>
> 這張表的成長維度是**版本累積**而非旅程數量：舊版永不刪除（Enrollment 綁著它），一條每週改一次的旅程兩年就 100 列。仍是萬級，結論不變。

### 熱路徑解法是快取，不是索引

事件進來要比對「哪些旅程要觸發」，是**每個事件都做一次**的熱路徑。但已發布的版本不可變，是完美的快取對象——Phase 1 應在記憶體常駐旅程定義，而不是靠索引硬查。

---

## 4. `enrollment_record`

正本：`02` §9（已有完整 DDL，照抄即可）

> 規格 §9 的 DDL 表名寫 `enrollment`，Notion 任務卡寫 `enrollment_record`。**以 `enrollment_record` 為準**（見 §7 決策 1），`02` §9 待回頭修正。

### 欄位分組

| 組 | 欄位 | 出處 |
|---|---|---|
| 識別 | `enrollment_id`(PK)、`tenant_id`、`journey_id`、`journey_version`、`profile_id` | `02` §2 |
| 狀態 | `status`、`current_node_id` | `02` §3 |
| **喚醒（僅實作層）** | `wake_type`、`next_wake_at`、`wake_event_name`、`wake_event_filter` | `02` §4 |
| G1 | `trigger_snapshot` JSONB NOT NULL | `02` §5 |
| G3 | `dedup_key` TEXT（可 null） | `02` §6 |
| G4 | `delivery_history` JSONB NOT NULL DEFAULT `'[]'` | `02` §7 |
| 變數 | `variables` JSONB NOT NULL DEFAULT `'{}'`，上限 32 KB | `02` §2 |
| 時間 | `enrolled_at`、`updated_at`、`terminated_at`、`termination_reason` | `02` §2 |

### ⚠️ 契約層／實作層邊界

`wake_type` / `next_wake_at` / `wake_event_name` / `wake_event_filter` 這四欄是契約層 `WakeCondition`（sealed interface）**在資料庫的展開形式**。

> 契約層用 `wakeAt`（語意：什麼時候該醒），實作層才有 `next_wake_at`（語意：掃描索引欄位）。**名字不同是刻意的。**
>
> 這四個名字出現在 `EnrollmentState` 或 `JourneyRuntime` 介面上就是違規（裁決 2）。`02` §10 第一條驗收要求以 ArchUnit 驗證。

### 索引與約束

每一個都對應 `04` §7 的一個介面方法。**漏一個，對應的方法就變全表掃描。**

> 與 `journey_definition`（§3 決定不加索引）相反：這張表每個客戶每次進旅程就一列、終態也不刪，會到**百萬列以上**，且 `lockDue` 是每秒都在跑的推進迴圈。「規模小就不用索引」的推論在這裡不成立。

| 索引 | 服務誰 | 為什麼是 partial |
|---|---|---|
| `uq_enrollment_dedup`<br>`(tenant_id, journey_id, profile_id, dedup_key)`<br>`WHERE dedup_key IS NOT NULL` | G3 併發去重 | `COOLDOWN` / `UNLIMITED` 模式的 `dedup_key` 是 null，不該佔唯一性 |
| `ix_enrollment_wake`<br>`(next_wake_at)`<br>`WHERE status = 'WAITING' AND next_wake_at IS NOT NULL` | `StateStore.lockDue()` | 只有 `WAITING` 需要被掃，其餘是死重量 |
| `ix_enrollment_wake_event`<br>`(tenant_id, profile_id, wake_event_name)`<br>`WHERE status = 'WAITING' AND wake_event_name IS NOT NULL` | `StateStore.findWaitingForEvent()` | **必須含 `profile_id`**，理由見 `04` §7 |
| `ix_enrollment_journey_active`<br>`(tenant_id, journey_id)`<br>`WHERE terminated_at IS NULL` | G10 緊急停止、全域退出的批次操作 | 終態實例不需要被批次操作掃到 |

> `uq_enrollment_dedup` 不只是索引，它是 **G3 併發正確性的唯一來源**。
> `02` §6 寫明「不可用先查再插判斷重複——那有競態，一定會漏」。5 個執行緒同時插入時，靠這個約束讓 4 個失敗，捕捉衝突後回傳既有 Enrollment。

### CHECK 約束：把規格上的上限變成資料庫擋得住的東西

`02` 對兩個 JSONB 欄位訂了明確上限，但**規格寫了不等於擋得住**：

| 欄位 | 規格上限 | 出處 |
|---|---|---|
| `delivery_history` | 最近 **50 筆**，超過丟棄最舊的 | `02` §7 |
| `variables` | **32 KB** | `02` §2 |

兩者都要下 `CHECK`：

```sql
CONSTRAINT ck_enrollment_delivery_history_size
    CHECK (jsonb_array_length(delivery_history) <= 50),
CONSTRAINT ck_enrollment_variables_size
    CHECK (octet_length(variables::text) <= 32768)
```

> **用 `octet_length(...::text)`，不要用 `pg_column_size()`。**
> `pg_column_size()` 回傳的是**壓縮後的實際儲存大小**，而 `02` §5 對同類上限寫的是「64 KB（**序列化後**）」——量的是 JSON 文字長度。
> 重複性高的 JSON 可以壓到十分之一，用 `pg_column_size` 會放行一份 300 KB 的 `variables`，只因為它壓縮後看起來很小。

理由是 README「RLS 的唯一例外」那句：**「讓遺漏成為不可能，而非靠自律」**。

上限若只活在規格裡，第一個忘記截斷的節點就會把它撐破，而且**不會有任何錯誤**——直到某天 `deliveryHistory` 長到讓每次 UPDATE 都重寫一大塊 TOAST，或 `variables` 塞爆訊息渲染。`CHECK` 讓違規在寫入當下就失敗，堆疊追蹤直接指向寫壞的那行程式碼。

> **`CHECK` 只負責攔截，不負責修剪。** 「超過 50 筆丟棄最舊的」仍是應用層的責任——`CHECK` 是應用層漏掉時的安全網，不是替代品。

> `delivery_history` 的完整稽核紀錄在 `delivery_task` 表（§10）。JSONB 這份是**給節點決策用的快取**，50 筆上限正是它能安全內嵌的前提。

---

## 5. `node_trace`

正本：`02` §8（`EnrollmentTrace`）

**append-only，唯一例外是「補完」一次。** 存在的理由是回答「為什麼這個客戶沒收到訊息」。等待節點進入時寫一列（`exited_at`、`out_port` 為 null），喚醒離開時以同一 `seq` 補上 `exited_at`、`out_port`、`decision_reason`、`evaluated`；`exited_at` 已有值的列不得再改。

| 欄位                | 型別          | 必填  | 說明                   |
| ----------------- | ----------- | --- | -------------------- |
| `enrollment_id`   | TEXT        | ✅   |                      |
| `seq`             | BIGINT      | ✅   | 遞增序號。見下方             |
| `tenant_id`       | TEXT        | ✅   | 契約 record 沒有，但共通約定要求 |
| `node_id`         | TEXT        | ✅   |                      |
| `node_type`       | TEXT        | ✅   |                      |
| `entered_at`      | TIMESTAMPTZ | ✅   |                      |
| `exited_at`       | TIMESTAMPTZ |     | 仍在該節點時為 null         |
| `out_port`        | TEXT        |     | 走了哪個出口               |
| `decision_reason` | TEXT        | ✅   | **強制非空**，見下方         |
| `evaluated`       | JSONB       | ✅   | 判斷時實際取到的值            |

### `seq` 不只是序號

它是冪等鍵 `{enrollmentId}:{nodeId}:{visitSeq}:{effectIndex}` 的**第三段**（README 共通約定）。

旅程允許含等待節點的合法迴圈——同一個 `SendMessageNode` 第二圈**必須真的再發一次**。`seq` 就是區隔「第 N 次重訪」的那個維度。少了它，第二圈會被當成重放而去重掉，客戶收不到第二則訊息。

### `decision_reason` 是強制的，不是選配

`02` §8 寫明：`ConditionNode` 判斷「是否 VIP」走了 `no`，就必須記下「會員等級=銀卡，不在 [金卡,白金卡] 中」。

> 沒有這個，客服每次都要找工程師撈日誌。

在 DDL 層下 `NOT NULL` 把它釘死，比寫在文件裡有效。

### 主鍵

| 項目  | 定義                     | 理由                                                  |
| --- | ---------------------- | --------------------------------------------------- |
| PK  | `(enrollment_id, seq)` | 天然唯一，且查詢永遠是「某個 enrollment 的完整軌跡，按序」——PK 的順序剛好就是掃描順序 |

---

## 6. `event`（最小版）

正本：`01` §3（欄位）、§7（去重）

| 欄位            | 型別          | 必填  | 說明                                                             |
| ------------- | ----------- | --- | -------------------------------------------------------------- |
| `event_id`    | TEXT        | ✅   | ULID，**由產生端提供**                                                |
| `tenant_id`   | TEXT        | ✅   | 由認證憑證推導，**不接受請求端指定**                                           |
| `source_type` | TEXT        | ✅   | `CUSTOMER_BEHAVIOR` / `EXTERNAL_SYSTEM` / `INTERNAL_OPERATION` |
| `source_id`   | TEXT        | 條件  | 見 `01` §4                                                      |
| `name`        | TEXT        | ✅   | `snake_case`                                                   |
| `occurred_at` | TIMESTAMPTZ | ✅   | **事件實際發生時間**                                                   |
| `received_at` | TIMESTAMPTZ | ✅   | 平台接收時間，由 Ingestion 填                                           |
| `identity`    | JSONB       | ✅   | `profile_id` 與 `identifiers` 至少擇一                              |
| `properties`  | JSONB       |     |                                                                |
| `context`     | JSONB       | 條件  | `INTERNAL_OPERATION` 必填 `actor_id`                             |
| `dispatched_at` | TIMESTAMPTZ |   | 扇出完成時間；null = 待派發（V2，§10）                                      |

### ⚠️ `occurred_at` 與 `received_at` 不可合併

兩者的差就是「遲到多久」。`01` §6 的 24 小時遲到門檻、`02` §4 的「遲到事件照樣喚醒」判定，全靠比對 `occurred_at` 與 `timeoutAt`。

> 補送的「券已核銷」若被門檻擋掉，客戶會收到「你的券快過期了」——**這正是遲到規則本來想避免的那種訊息。**

### 主鍵

```
PRIMARY KEY (tenant_id, event_id)
```

`01` §7 的去重鍵就是這組。重複的 `event_id` 撞主鍵 → 丟棄並回 `200`（不是錯誤，是正常重試）。

> 去重視窗規格寫 7 天，但主鍵是永久的。Phase 0 先讓它永久——見下方，這件事與未來的分區直接衝突。

### 🚨 分區鍵與去重鍵的衝突（Phase 3 前置決策）

`event` 表遲早要做時間分區（億級、有天然時間維度）。**但不能直接加**，因為：

> **Postgres 要求分區表的所有 unique constraint 必須包含分區鍵，沒有例外。**

所以 partition by `occurred_at` 會迫使 PK 從 `(tenant_id, event_id)` 變成 `(tenant_id, event_id, occurred_at)`——而那會**靜默打爛去重**：同一個 `event_id` 配不同時間戳變成兩列，`01` §7 的「重複 `event_id` 丟棄並回 200」失效，重送的事件會被當成新事件重複觸發旅程。

**不會有任何錯誤訊息。** 這是最糟的那種失效。

#### Phase 3 的解法方向

`01` §7 本來就說去重視窗是 **7 天**，而目前的 PK 是**永久**去重——本來就過度。正解是：

1. 分區上線時，PK 改為含分區鍵
2. 去重改由應用層或 Redis 承擔，實作規格寫明的 7 天視窗
3. **兩件事必須同一次上線**，不可先加分區再補去重

> 在此之前，`event` 表**不得加分區**。誰要動它，先讀這一節。

### 索引

| 索引 | 欄位 | 服務哪一類查詢 |
| --- | --- | --- |
| `ix_event_name_time` | `(tenant_id, name, occurred_at DESC)` | `WHERE tenant_id = ? AND name = ? AND occurred_at > ?`，例如行為條件「最近 30 天有沒有 `order_completed`」 |

#### ⚠️ 這裡**不加** `WHERE`

上表「服務哪一類查詢」欄寫的是**執行時的查詢長什麼樣**，不是要寫進索引定義的條件。兩個 `WHERE` 不是同一件事：

| | 誰寫的 | 何時決定 | 例 |
|---|---|---|---|
| **索引的 `WHERE`**（partial index） | schema | 建表時，**永久固定** | `WHERE status = 'WAITING'` |
| **查詢的 `WHERE`** | 應用程式 | 每次查詢，**每次不同** | `WHERE name = 'order_completed'` |

`tenant_id` / `name` / `occurred_at` 是查詢條件，由索引的**欄位順序**（前綴匹配）服務，不進索引定義。

把它們寫進 partial index 會有兩個後果：

1. `name = 'order_completed'` 寫死 → 索引只服務這一種事件，`page_viewed` 等全部用不到
2. 時間窗**根本建不起來**——partial index 的述詞必須是 `IMMUTABLE`，而 `now()` 是 `STABLE`，Postgres 會直接拒絕：
   ```
   ERROR:  functions in index predicate must be marked IMMUTABLE
   ```
   這不是語法問題，是本質的：**索引是靜態結構，「最近 30 天」每一秒都在移動。**

> 口訣：**partial index 的 `WHERE` 裡不能出現「現在」。**
>
> 對照 §4 的 `enrollment_record`：那些 `WHERE status = 'WAITING'` 是常數比較（`IMMUTABLE`），且回答的是「哪些列**永遠不值得**進索引」——終態實例再也不會被 `lockDue` 掃到。那是靜態事實，所以成立。

---

## 7. 落地決策紀錄

翻譯過程中規格沒講清楚、由本檔裁決的點。**日後改動前先看一眼當初為什麼這樣定。**

| # | 題目 | 裁決 | 理由 |
|---|---|---|---|
| 1 | 表名 `enrollment` 還是 `enrollment_record` | **`enrollment_record`** | 貼合「實作層 Record」的命名意圖，與契約層 `EnrollmentState` 一眼可辨。`02` §9 的 DDL 待同步修正 |
| 2 | `journey_definition` 主鍵 | **`(journey_id, version)`** | 版本並存是硬需求，見 §3 |
| 3 | `created_at` / `published_at` 契約沒列，要不要加 | **要加** | 「草稿不佔版本、發布時才配版本號」必須答得出「v2 何時發布」。事後補要回填 |
| 4 | Phase 0 要不要開 RLS | **不開** | 有 `ENABLE` 沒 `POLICY` = 全部拒絕且不報錯。與 policy 一起排 Phase 1b |
| 5 | enum 用 Postgres `ENUM` 還是 `TEXT` | **`TEXT`** | `ALTER TYPE` 跨 migration 很痛，狀態機還會演進 |
| 6 | 要不要加外鍵 | **不加** | `node_trace` 每次節點推進都寫，外鍵檢查在熱路徑上；且 `enrollment_record → journey_definition` 的複合外鍵會擋住旅程封存。完整性靠應用層與單一交易 |
| 7 | `CREATE TABLE IF NOT EXISTS` | **不用** | Flyway 靠 `flyway_schema_history` 保證只跑一次。`IF NOT EXISTS` 會讓「表已存在但結構不同」靜默通過，得到一個與 migration 不符的 schema 卻沒有任何錯誤 |
| 8 | `event` / `node_trace` 要不要現在做時間分區 | **不做** | Phase 0 無真實流量。`event` 另有分區鍵 vs 去重鍵的衝突未解（§6）；`node_trace` 的查詢永遠是「某條 enrollment 的完整軌跡」，依時間分區會讓跨月旅程的軌跡散落多個分區，比不分區更慢——真要分區該用 `enrollment_id` hash |
| 9 | `delivery_history` 要不要改成獨立關聯表 | **不改** | `02` §7 已界定：JSONB 這份是節點決策用的**快取**（上限 50 筆），完整稽核紀錄在獨立的 `delivery_task` 表（§10）。兩者並存不是二選一。改為 join 會讓節點執行時多一次查詢 |
| 10 | 規格上的大小上限要不要進 DDL | **要，下 `CHECK`** | `delivery_history` 50 筆、`variables` 32 KB 若只活在規格裡，撐破時**不會有任何錯誤**。見 §4 |

---

## 8. Flyway 版本台帳

| 版本 | 檔名 | 內容 |
|---|---|---|
| V1 | `V1__core_tables.sql` | 本檔 §3~§6 的四張表（自 `../CDP` 的 V2 原樣搬入） |
| V2 | `V2__engine_pipeline.sql` | §10：`event.dispatched_at`、`ix_enrollment_profile`、`effect_outbox`、`delivery_task`、`mock_channel_message` |
| V3 | `V3__event_dispatch_retry.sql` | §11：`event` 派發重試欄位、`ix_event_dispatch_queue`（取代 `ix_event_undispatched`） |
| V4 起 | （v0.2.0） | role 三分與 RLS（`docs/design/INIT-01.md` R2） |

### 鐵則

**已套用的 migration 永不修改。** 改動 → 新增 V3、V4。

這與 `03` §1 的旅程版本化規則是同一個道理：已經有人依賴的東西不能就地改，只能往後接。現在漏掉的欄位，日後都要靠新 migration 加欄位 + 回填。

---

## 9. 驗收條件

`02` §10 的清單中，V1～V3 能覆蓋的部分：

- [ ] `mvn flyway:migrate` 在乾淨資料庫上從 V1 跑到 V3 無錯
- [ ] 同一 `(tenant_id, journey_id, profile_id, dedup_key)` 併發插入 5 次，4 次撞 `uq_enrollment_dedup`（**由 DB 約束保證，不是先查再插**）
- [ ] 同一 `journey_id` 可同時存在 v1 與 v2 兩列
- [ ] `EXPLAIN` 確認 `lockDue` 的查詢走 `ix_enrollment_wake`，不是 Seq Scan
- [ ] 重複 `(tenant_id, event_id)` 插入撞主鍵
- [ ] `node_trace` 的 `decision_reason` 為 null 時插入失敗
- [ ] `delivery_history` 塞第 51 筆時被 `CHECK` 擋下
- [ ] `variables` 塞超過 32 KB 時被 `CHECK` 擋下

> 建議用 Testcontainers 寫成測試。只看 `mvn flyway:migrate` 跑過證明不了索引有沒有被用到——而索引用不到，正是這張單最容易留下的坑。

---

## 10. v0.1.0 新增（`V2__engine_pipeline.sql`）

DDL 正本為 `api/src/main/resources/db/migration/V2__engine_pipeline.sql`；這裡只記語意。

| 表／欄位 | 語意 | 鍵與索引 |
|---|---|---|
| `event.dispatched_at` | 扇出（比對 EventSource、建立 Enrollment）結束時間；遲到 > 24h、重試用完（§11）也標上但不建立（`01` §6） | V3 起改 `ix_event_dispatch_queue`（§11） |
| `enrollment_record` 查詢索引 | 軌跡頁依客戶列 Enrollment，新到舊 | `ix_enrollment_profile (tenant_id, profile_id, enrolled_at DESC, enrollment_id DESC)` |
| `effect_outbox` | 與狀態同一交易寫入的 Effect；`status` = `PENDING`／`PUBLISHED`／`CANCELLED`；relay 只推 `PENDING` 且 `available_at <= now`；重試退避改 `available_at`，不用 MQ 延遲 | PK `idempotency_key`；`ix_effect_outbox_due (available_at) WHERE status='PENDING'` |
| `delivery_task` | 每個 SendMessage 一列，consumer 冪等依據（已 `SENT` 即丟棄重投）；`status` = `PENDING`／`SENT`／`SKIPPED`／`FAILED`／`CANCELLED`，`SKIPPED` 必帶 `skip_reason`（CHECK）；`visit_seq` = `node_trace.seq` | PK `idempotency_key`；`ix_delivery_task_enrollment (tenant_id, enrollment_id, attempted_at DESC, idempotency_key DESC)` |
| `mock_channel_message` | Mock 通道「已送出」的訊息，與 `delivery_task` 同一交易寫入；驗收「恰好一則」數這張表 | PK `idempotency_key` |

`enrollment_record.delivery_history` 只由 consumer 以單一 `UPDATE` 追加並修剪到 50 筆；`StateStore.save` 不寫這個欄位，兩者不互相覆蓋。

### `effect_outbox.effect_type` 與 `payload`

寫入端：`JdbcStateStore.save`（`effect_type` = `Effect` 子型別的簡單類別名；`payload` = 以應用程式 `JsonMapper`（`snake_case`）序列化該 record）。改 record 名稱或元件名 = 既有列不相容，須新 migration 回填。

| `effect_type` | 來源 | `payload` 鍵 | routing key → queue | 消費者 |
|---|---|---|---|---|
| `SendMessage` | `Effect.SendMessage`（`04` §5） | `idempotency_key` string（= 列 PK）、`template_id` string、`channel_id` string、`variables` object（v0.1.0 一律 `{}`） | `send-message` → `cdp.effects.send-message`（`RabbitTopology`） | `DeliveryConsumer` → `ChannelEffectExecutor` |

- v0.1.0 只會出現 `SendMessage`；`04` §5 其他 Effect 在用到的那期加一列本表。relay 遇未列的 `effect_type`：不發佈、留 `PENDING`、記錯誤日誌
- MQ 訊息 body 只有 `idempotency_key`（DESIGN-01 做法 #4）；consumer 以 `OutboxStore.find` 讀回整列。`payload` 缺鍵或型別不符 = 無法解析 → DLQ
- 路由與歸屬資訊從**欄位**讀，不從 `payload` 讀：`delivery_task` 的 `tenant_id` `enrollment_id` `journey_id` `journey_version` `node_id` `visit_seq` 取同名欄位；`template_id` `channel_id` 取 `payload`；`channel_type` = `MOCK`、`message_type` = `NULL`（v0.1.0）
- `delivery_history` 追加的 `DeliveryRecord.traceSeq` = `visit_seq`；`attempted_at` 取注入的 `Clock`

---

## 11. 事件派發重試（`V3__event_dispatch_retry.sql`，D-024）

DDL 正本為 `api/src/main/resources/db/migration/V3__event_dispatch_retry.sql`；實作單 FIX-03（`docs/design/DESIGN-01.md`「FIX-03」）。

| 欄位／索引 | 語意 |
|---|---|
| `dispatch_attempts` INT NOT NULL DEFAULT 0 | 派發失敗次數（一輪中任一綁定失敗算一次）；`>= 0`（CHECK） |
| `next_dispatch_at` TIMESTAMPTZ | 下次可派發時間；`NULL` = 未失敗過、立即可派發 |
| `last_dispatch_error` TEXT | 最近一次失敗摘要 `<例外類別簡名>: <訊息>`，截斷到 1000 字（CHECK）；不寫事件屬性值、不經 API 輸出 |
| `dispatch_outcome` TEXT | `DISPATCHED`（綁定全部成功或無綁定）／`LATE`（遲到 > 24h）／`EXHAUSTED`（重試用完）；未派發為 `NULL`；非 `NULL` 時 `dispatched_at` 必有值（CHECK）；V3 依 `received_at - occurred_at` 回填既有列 |
| `ix_event_dispatch_queue (received_at, event_id) WHERE dispatched_at IS NULL` | 派發佇列；`ORDER BY received_at, event_id` 直接走索引 |

派發規則（`EventDispatcher`；時間一律取注入的 `Clock`）：

1. 取列：`dispatched_at IS NULL AND (next_dispatch_at IS NULL OR next_dispatch_at <= now)`，`ORDER BY received_at, event_id`，`FOR UPDATE SKIP LOCKED`。退避中的事件不進批次，不占批首
2. 綁定全部成功 → `dispatched_at = now`、`DISPATCHED`；遲到 → `LATE`
3. 任一綁定失敗 → `dispatch_attempts + 1`、寫 `last_dispatch_error`；達上限 → `dispatched_at = now`、`EXHAUSTED`、ERROR 日誌；未達 → `next_dispatch_at = now + min(初始退避 × 2^(attempts−1), 退避上限)`
4. 已成功的綁定（NESTED 已提交）保留；重試時靠 `dedup_key = event_id` 不重建，只補失敗的綁定；`EXHAUSTED` 後失敗的綁定不建立 Enrollment
5. 整批交易失敗（DB 不可用）→ 無列更新、不計次

設定變數（值在 `.env`）：`ENGINE_DISPATCH_MAX_ATTEMPTS`（上限次數）、`ENGINE_DISPATCH_BACKOFF_INITIAL`、`ENGINE_DISPATCH_BACKOFF_MAX`（ISO Duration）。

過渡：V3 合併到 FIX-03 合併之間，`markDispatched` 不寫 `dispatch_outcome`（為 `NULL`）；FIX-03 起每次標派發必寫。
