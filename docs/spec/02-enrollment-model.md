# 02 — Enrollment 資料模型

> 對應補強項 **G1**（觸發事件快照）、**G3**（重入去重鍵）、**G4**（發送歷史）。
> 依 README **裁決 2**，分「契約層」與「實作層」兩層。

Enrollment 是「某個客戶走某條旅程的某一次」，是整個引擎的核心實體。
它記住什麼，決定了平台能做到什麼——這是 Phase 0 最重要的一份規格。

---

## 1. 兩層模型

| 層 | 型別 | 誰看得到 | 換 Temporal 時 |
|---|---|---|---|
| **契約層** | `EnrollmentState` | 節點、API、報表、客服查詢 | **不變** |
| **實作層** | `EnrollmentRecord`（資料表） | 只有 `StateStore` 實作 | **整個換掉** |

**鐵則**：`JourneyRuntime` 介面上出現的任何型別，不得引用 `EnrollmentRecord` 或任何資料表欄位語意（特別是 `next_wake_at`）。

---

## 2. `EnrollmentState`（契約層）

```java
record EnrollmentState(
    String              enrollmentId,      // ULID
    String              tenantId,
    String              journeyId,
    int                 journeyVersion,    // 建立當下綁定，終生不變
    String              profileId,
    EnrollmentStatus    status,
    String              currentNodeId,
    WakeCondition       wakeCondition,     // status=WAITING 時必有，否則 null
    TriggerSnapshot     triggerSnapshot,   // G1
    String              dedupKey,          // G3，可為 null
    Map<String, Object> variables,         // 旅程變數
    List<DeliveryRecord> deliveryHistory,  // G4
    Instant             enrolledAt,
    Instant             updatedAt,
    Instant             terminatedAt,      // 未結束為 null
    String              terminationReason  // 未結束為 null
) {}
```

### 欄位說明

| 欄位 | 說明 |
|---|---|
| `journeyVersion` | **建立時綁定，之後旅程改版不影響**。這是「五萬人卡在等待節點時旅程被改」的答案 |
| `currentNodeId` | 當前所在節點。`WAITING` 時指的是**造成等待的那個節點** |
| `wakeCondition` | 「在等什麼」。**契約層只表達語意，不表達喚醒機制** |
| `variables` | 節點寫入的旅程變數（A/B 分組、折扣碼）。**上限 32 KB** |
| `terminationReason` | 結束原因，供分析。見 §6 |

---

## 3. 狀態機

```
                    ┌─────────┐
     建立 ─────────►│ PENDING │
                    └────┬────┘
                         │ 開始推進
                         ▼
                    ┌─────────┐
        ┌──────────►│ ACTIVE  │◄──────────┐
        │           └────┬────┘           │
        │                │ 遇 Wait 節點    │ wakeCondition 滿足
        │                ▼                │
        │           ┌─────────┐           │
        │           │ WAITING ├───────────┘
        │           └────┬────┘
        │                │
        │ 恢復           ├──────────────┐ 暫停(freeze)
        │                │              ▼
        │                │        ┌───────────┐
        └────────────────┼────────┤ SUSPENDED │
                         │        └───────────┘
                         │
    ┌────────┬───────────┼───────────┬──────────┐
    ▼        ▼           ▼           ▼          ▼
COMPLETED GOAL_MET    EXITED      FAILED    ABORTED
(走到終點)(達標退出) (條件退出)  (不可恢復) (緊急停止)
```

### 狀態定義

| 狀態 | 終態 | 說明 |
|---|---|---|
| `PENDING` | | 已建立，尚未開始推進。存在時間極短 |
| `ACTIVE` | | 正在推進節點 |
| `WAITING` | | 掛起中，等 `wakeCondition` |
| `SUSPENDED` | | 旅程暫停且模式為「凍結」。恢復後回到 `WAITING` 或 `ACTIVE` |
| `COMPLETED` | ✅ | 走到 `EndNode` |
| `GOAL_MET` | ✅ | 命中 Goal 提前結束 |
| `EXITED` | ✅ | 全域退出條件、`ExitNode`、`FilterNode` 過濾 |
| `FAILED` | ✅ | 不可恢復錯誤（重試耗盡） |
| `ABORTED` | ✅ | **緊急停止（G10）** 或旅程被封存 |

### 轉移規則

- **終態不可再轉移**。任何對終態 Enrollment 的推進請求直接忽略（不是錯誤）
- `GOAL_MET` 與全域退出**優先於一切**：只要條件命中，不論當前在哪個節點、是否在 `WAITING`，立即轉終態並取消計時器。
  兩者的來源分別是 `settings.goal` 與 `settings.global_exit`（見 `03-journey-definition.md` §3），
  由引擎在推進迴圈的**每一次**檢查——**不是走到某個節點才判定**
- `SUSPENDED` 只能由旅程層操作觸發，節點不能主動進入
- 進入任何終態時，**必須**取消該 Enrollment 所有未觸發的計時器與未送出的訊息（見 `05-delivery-pipeline.md` 取消點）

---

## 4. `WakeCondition`

```java
sealed interface WakeCondition {

    // 等一段時間 / 等到某時刻
    record Timer(Instant wakeAt) implements WakeCondition {}

    // 等事件發生，逾時走另一條路
    record Event(
        String              eventName,
        Map<String, Object> filter,      // 事件屬性條件
        Instant             timeoutAt    // null = 不逾時
    ) implements WakeCondition {}

    // 等人工或外部訊號，無逾時
    record Signal(String signalName) implements WakeCondition {}
}
```

**注意**：`Event` 同時帶事件條件與逾時時間——`WaitForEventNode` 兩條出口就靠它。引擎必須同時登記事件訂閱與計時器，**先到者勝，另一個立即取消**。

**遲到事件照樣喚醒。** `01-event-contract.md` §6 的 24 小時門檻只擋「建立新 Enrollment」，不擋喚醒——判定一律用事件的 `occurred_at` 與 `timeoutAt` 比較：早於逾時走 `occurred`、晚於逾時走 `timeout`。
> 補送的「券已核銷」若被門檻擋掉，客戶會收到「你的券快過期了」——**這正是遲到規則本來想避免的那種訊息**。

> 契約層用 `wakeAt`（語意：什麼時候該醒），實作層才有 `next_wake_at`（語意：掃描索引欄位）。名字不同是刻意的。

---

## 5. G1 — 觸發事件快照

### 為什麼要存

棄單挽回的訊息要顯示「你購物車裡的保濕精華液和洗面乳」。這份資料只存在於**觸發當下的那個事件**裡——三天後訊息要發時，購物車可能已經變了。

### 結構

```java
record TriggerSnapshot(
    String              eventId,
    String              eventName,
    Instant             occurredAt,
    String              sourceType,
    Map<String, Object> properties,       // 事件屬性深拷貝
    Map<String, Object> profileSnapshot   // 進入當下的 Profile 屬性子集
) {}
```

### 使用規則

| 用途 | 可以用嗎 |
|---|---|
| 訊息模板個人化（`{{trigger.items}}`） | ✅ 主要用途 |
| G3 去重鍵抽取 | ✅ |
| 稽核「當時為什麼這個人會進來」 | ✅ |
| **條件判斷**（`ConditionNode`） | ⚠️ **原則上不可** |

> **重要**：條件判斷一律用**當下最新**的 Profile 與行為資料（見計劃書附錄 B 原則 ①）。
> `profileSnapshot` 只供**訊息個人化與稽核**。
> 若某節點確實要判斷「進入當下的值」，必須在旅程定義中明確寫成 `trigger.xxx`，讓意圖在畫布上看得見。

### 大小限制與截斷

- 上限 **64 KB**（序列化後）
- 超過時依序截斷：
  1. 陣列型屬性保留**前 20 筆**，附加 `_truncated: true` 與 `_total_count: N`
  2. 仍超過 → 保留白名單欄位（旅程定義中被模板引用的），其餘丟棄
  3. 仍超過 → 拒絕建立 Enrollment 並告警
- `profileSnapshot` 只存**旅程定義有引用到的欄位**，不是整份 Profile

---

## 6. G3 — 重入策略與去重鍵

### 五種模式

| 模式 | `dedupKey` 值 | 唯一約束 | 額外檢查 |
|---|---|---|---|
| `ONCE` | 固定字串 `"__ONCE__"` | ✅ | — |
| `DEDUP_KEY` | 從觸發事件抽取 | ✅ | — |
| `COOLDOWN` | `null` | ❌ | 最近一次 `terminatedAt` + 冷卻期 |
| `DEDUP_KEY_WITH_COOLDOWN` | 從觸發事件抽取 | ✅ | 同上 |
| `UNLIMITED` | `null` | ❌ | — |

### 去重鍵怎麼抽

旅程定義中以 JSONPath 指定：

```json
"reentry": {
  "mode": "DEDUP_KEY",
  "dedupKeyPath": "$.trigger.properties.cart_id"
}
```

抽取結果為 null 或空字串時的處理：**視為 `UNLIMITED`**（不設約束），並記錄警告。
> 理由：寧可多發一次，也不要因為抽不到鍵就靜默把客戶擋在旅程外。

### 唯一性約束（實作層）

```sql
CREATE UNIQUE INDEX uq_enrollment_dedup
  ON enrollment (tenant_id, journey_id, profile_id, dedup_key)
  WHERE dedup_key IS NOT NULL;
```

### 併發處理

同一個 `cart_id` 在同一毫秒觸發 5 次時：

1. 5 個執行緒同時嘗試插入
2. 資料庫唯一約束讓 4 個失敗
3. 捕捉唯一鍵衝突 → **視為重複，回傳既有 Enrollment，不視為錯誤**

**不可用「先查再插」判斷重複**——那有競態，一定會漏。以資料庫約束為準。

### 冷卻期基準

冷卻期從**上一次 Enrollment 的 `terminatedAt`** 起算，不是 `enrolledAt`。
> 理由：一條跑 30 天的旅程，若從進入起算 7 天冷卻，客戶可能在還沒走完時就又被拉進來。

---

## 7. G4 — 發送歷史

### 結構

```java
record DeliveryRecord(
    String        nodeId,
    long          traceSeq,          // 產生它的 EnrollmentTrace.seq（= 冪等鍵 visitSeq），供軌跡對應
    String        templateId,
    String        channelType,       // LINE / EMAIL / SMS / APP_PUSH
    String        channelId,
    MessageType   messageType,       // TRANSACTIONAL / MARKETING
    DeliveryStatus status,           // SENT / SKIPPED / FAILED
    SkipReason    skipReason,        // status=SKIPPED 時必填
    Instant       attemptedAt,
    String        deliveryId         // 可為 null（SKIPPED 時）
) {}
```

`SkipReason`：`CONSENT` / `FREQUENCY_CAP` / `QUIET_HOURS` / `PRIORITY_LOST` / `BUDGET_EXCEEDED` / `KILL_SWITCH`

### 用途

| 用途 | 怎麼用 |
|---|---|
| **換通道重試** | 讀最後一筆 `status=SENT` 的 `channelType`，避開它 |
| 避免同旅程重複發同一模板 | 檢查 `templateId` 是否已存在 |
| 客服排查 | 直接看得出「發了沒、沒發是為什麼」 |

### 上限

保留**最近 50 筆**。超過時丟棄最舊的，完整紀錄仍在 `delivery_task` 表中可查（`07` §10）。
> `deliveryHistory` 是給節點做決策用的快取，不是稽核來源。

---

## 8. 節點軌跡（可觀測性）

獨立於 Enrollment 的 **append-only** 紀錄，回答「為什麼這個客戶沒收到訊息」。

```java
record EnrollmentTrace(
    String  enrollmentId,
    long    seq,               // 遞增序號
    String  nodeId,
    String  nodeType,
    Instant enteredAt,
    Instant exitedAt,          // 仍在該節點時為 null
    String  outPort,           // 走了哪個出口
    String  decisionReason,    // 為什麼走這個出口（人可讀）
    Map<String, Object> evaluated  // 判斷時實際取到的值
) {}
```

**`decisionReason` 與 `evaluated` 是強制的，不是選配。**
`ConditionNode` 判斷「是否 VIP」走了 `no`，就必須記下「會員等級=銀卡，不在 [金卡,白金卡] 中」。沒有這個，客服每次都要找工程師撈日誌。

---

## 9. 實作層資料表（自建路線）

```sql
CREATE TABLE enrollment (
    enrollment_id      TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    journey_id         TEXT NOT NULL,
    journey_version    INT  NOT NULL,
    profile_id         TEXT NOT NULL,
    status             TEXT NOT NULL,
    current_node_id    TEXT NOT NULL,

    -- 契約層的 wakeCondition 在此展開為可索引欄位
    wake_type          TEXT,           -- TIMER / EVENT / SIGNAL
    next_wake_at       TIMESTAMPTZ,    -- ⚠️ 僅實作層，不得外洩到契約
    wake_event_name    TEXT,
    wake_event_filter  JSONB,

    trigger_snapshot   JSONB NOT NULL, -- G1
    dedup_key          TEXT,           -- G3
    variables          JSONB NOT NULL DEFAULT '{}',
    delivery_history   JSONB NOT NULL DEFAULT '[]',  -- G4

    enrolled_at        TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    terminated_at      TIMESTAMPTZ,
    termination_reason TEXT
);

-- G3 去重
CREATE UNIQUE INDEX uq_enrollment_dedup
  ON enrollment (tenant_id, journey_id, profile_id, dedup_key)
  WHERE dedup_key IS NOT NULL;

-- 計時器掃描（只掃 WAITING，避免全表）
CREATE INDEX ix_enrollment_wake
  ON enrollment (next_wake_at)
  WHERE status = 'WAITING' AND next_wake_at IS NOT NULL;

-- 事件喚醒比對（必須含 profile_id：查的是「這位客戶在等這個事件」）
CREATE INDEX ix_enrollment_wake_event
  ON enrollment (tenant_id, profile_id, wake_event_name)
  WHERE status = 'WAITING' AND wake_event_name IS NOT NULL;

-- 全域退出 / 緊急停止時依旅程批次操作
CREATE INDEX ix_enrollment_journey_active
  ON enrollment (tenant_id, journey_id)
  WHERE terminated_at IS NULL;

ALTER TABLE enrollment ENABLE ROW LEVEL SECURITY;
```

> `next_wake_at` 出現在資料表是允許的，出現在 `EnrollmentState` 或 `JourneyRuntime` 介面則是違規。

### 推進時的鎖

```sql
SELECT * FROM enrollment
 WHERE status = 'WAITING' AND next_wake_at <= now()
 ORDER BY next_wake_at
 LIMIT 100
 FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` 讓多個 worker 併發推進不互相阻塞。

---

## 10. 驗收條件

- [ ] `EnrollmentState` 與 `JourneyRuntime` 介面中不出現 `next_wake_at`（可用編譯期檢查或 ArchUnit 規則驗證）
- [ ] 同一 `cart_id` 併發觸發 5 次，只建立 1 個 Enrollment，另 4 次回傳既有實例且不報錯
- [ ] 三天後發出的訊息，仍能正確帶出觸發當下購物車的商品名稱（G1）
- [ ] 觸發快照超過 64 KB 時，陣列被截斷為前 20 筆並標記 `_truncated`
- [ ] 旅程從 v1 改為 v2 後，進行中的 Enrollment 仍依 v1 定義執行
- [ ] 第二波訊息能讀 `deliveryHistory` 避開第一波用過的通道（G4）
- [ ] 命中全域退出條件時，`WAITING` 中的實例立即轉 `EXITED` 且計時器被取消
- [ ] 每個節點的軌跡都有非空的 `decisionReason`
- [ ] 服務重啟後，`WAITING` 中的實例能被正確喚醒續跑
