# 06 — 受眾條件語言與資料字典

> 核心要求：**行銷人員自己圈受眾，平台不預設任何條件。**
> 平台不知道什麼叫「VIP」、什麼叫「沉睡客戶」。它只知道有哪些欄位、哪些事件、可以套哪些運算子。

同一份條件樹在三處複用：`AudienceSourceNode`（旅程入口）、`ConditionNode` / `FilterNode`（旅程途中）、`EventSourceNode.profile_filter`（觸發時再篩）。

---

## 1. 資料字典（Field Registry）

**這是「不預設條件」的實作關鍵。** 篩選器的所有選項都從字典長出來，不從程式碼長出來。

```java
record FieldDefinition(
    String   fieldKey,          // member_tier
    String   displayName,       // 會員等級
    DataType dataType,
    FieldSource source,         // IMPORTED / EVENT_DERIVED / COMPUTED
    boolean  filterable,
    Sensitivity sensitivity,    // PUBLIC / INTERNAL / PII / RESTRICTED
    List<Object> allowedValues, // 低基數欄位自動列舉，供下拉選單
    Object   exampleValue,
    String   description,
    Instant  registeredAt
) {}

enum DataType { STRING, NUMBER, BOOLEAN, DATE, DATETIME, ENUM, ARRAY }
```

### 註冊流程

```
Ingestion 收到新欄位 / 新事件
      ↓
自動寫入字典，狀態 = PENDING
      ↓
管理員確認型別、顯示名稱、敏感等級
      ↓
狀態 = ACTIVE，篩選器立刻可用
```

**全程不需改任何程式碼。** 這是 Phase 3 的驗收條件之一。

### 事件字典

事件另有一份，記錄事件名稱與其屬性：

```java
record EventDefinition(
    String eventName,           // order_completed
    String displayName,         // 完成訂單
    Map<String, FieldDefinition> properties,
    long   observedCount,       // 近 30 天觀察到的次數，供 UI 排序
    Instant firstSeenAt, Instant lastSeenAt
) {}
```

### 敏感等級與權限

| 等級 | 可篩選 | 樣本檢視 |
|---|---|---|
| `PUBLIC` | 全部角色 | 明文 |
| `INTERNAL` | 全部角色 | 明文 |
| `PII` | 需 `CAN_FILTER_PII` | **遮罩**（`a***@example.com`） |
| `RESTRICTED` | 需明確授權 | 不顯示 |

---

## 2. 條件樹結構

```json
{
  "op": "AND",
  "children": [
    { ...條件或子群組... }
  ]
}
```

`op`：`AND` / `OR` / `NOT`
巢狀無深度限制（實務上建議 UI 限制 5 層）。

葉節點是三種條件之一：`attribute`、`behavior`、`segment`。

---

## 3. 三種條件

### ① 屬性條件（`attribute`）

「這個人是什麼樣的人」

```json
{
  "type": "attribute",
  "field": "member_tier",
  "operator": "IN",
  "value": ["GOLD", "PLATINUM"]
}
```

### ② 行為條件（`behavior`）

「這個人做過什麼」——**分眾的靈魂，多數平台做得太淺的地方**

```json
{
  "type": "behavior",
  "event": "order_completed",
  "occurrence": "AGGREGATE",
  "aggregation": { "function": "SUM", "field": "amount" },
  "operator": "GTE",
  "value": 50000,
  "time_window": { "kind": "RELATIVE", "last": "P365D" },
  "event_filter": {
    "op": "AND",
    "children": [
      { "type": "attribute", "field": "category", "operator": "EQ", "value": "COSMETICS" }
    ]
  }
}
```

#### `occurrence` — 五種問法

| 值 | 問的問題 | 需要 `aggregation` |
|---|---|---|
| `ANY` | 做過嗎 | ❌ |
| `NEVER` | 從未做過 | ❌ |
| `COUNT` | 做過幾次 | ❌（隱含 count） |
| `AGGREGATE` | 總和／平均／最大／相異數 | ✅ |
| `LAST_OCCURRED` | **最近一次距今多久** | ❌ |
| `FIRST_OCCURRED` | 第一次距今多久 | ❌ |

> **`LAST_OCCURRED` 必須有。** 沒有它就圈不出沉睡客戶、圈不出回購週期到了的人——這是電商最常用的兩個受眾。

`aggregation.function`：`SUM` / `AVG` / `MAX` / `MIN` / `DISTINCT_COUNT`

### ③ 受眾條件（`segment`）

「這個人在不在另一個名單裡」

```json
{ "type": "segment", "segment_id": "01JCXYZ...", "is_member": false }
```

讓受眾包可以互相組合、層層堆疊。**因此不需要獨立的「受眾判斷節點」**——`ConditionNode` 直接吃這種條件。

---

## 4. 運算子（依型別決定）

| 資料型別 | 可用運算子 |
|---|---|
| `STRING` | `EQ` `NEQ` `CONTAINS` `NOT_CONTAINS` `STARTS_WITH` `ENDS_WITH` `IN` `NOT_IN` `IS_NULL` `IS_NOT_NULL` |
| `NUMBER` | `EQ` `NEQ` `GT` `GTE` `LT` `LTE` `BETWEEN` `IS_NULL` `IS_NOT_NULL` |
| `BOOLEAN` | `EQ` `IS_NULL` |
| `DATE` / `DATETIME` | `BEFORE` `AFTER` `BETWEEN` `WITHIN_LAST` `MORE_THAN_AGO` **`WITHIN_NEXT`** `IS_NULL` |
| `ENUM` | `IN` `NOT_IN` `EQ` `NEQ` `IS_NULL` |
| `ARRAY` | `CONTAINS_ANY` `CONTAINS_ALL` `NOT_CONTAINS` `IS_EMPTY` |

**UI 必須依字典中的 `dataType` 動態決定可選運算子。** 不可寫死清單。

> **`WITHIN_NEXT`（未來 N 天內）不可省略。** 日期欄位只有「往回看」的運算子時，
> 「生日在未來 7 天內」「合約 30 天後到期」「會員資格即將降級」全部圈不出來——
> 而 §10 的「生日將至」範本與計劃書附錄 C 場景二（生日禮）都需要它。
> `WITHIN_LAST` 與 `WITHIN_NEXT` 是一對，只做一半等於只支援回顧、不支援預警。

---

## 5. 時間表達（三種寫法）

```java
sealed interface TimeWindow {
    record Relative(Duration last) implements TimeWindow {}
    record Absolute(Instant from, Instant to) implements TimeWindow {}
    record ProfileRelative(String anchorField, Duration offset, Direction direction) implements TimeWindow {}
    record SinceEnrollment() implements TimeWindow {}   // 僅旅程內可用
}
```

| 寫法 | 例子 | 用途 |
|---|---|---|
| `Relative` | 近 30 天 | 最常用，受眾隨時間滾動 |
| `Absolute` | 2026/01/01 ~ 03/31 | 檔期回顧 |
| **`ProfileRelative`** | 註冊滿 30 天、生日前 7 天、上次購買後 60 天 | **生命週期經營，不能少** |
| `SinceEnrollment` | 進入旅程之後 | 僅 `ConditionNode` / `FilterNode` 可用 |

> `ProfileRelative` 是分水嶺。少了它，「註冊滿 30 天還沒下單的人」就圈不出來——而這是新客轉換最重要的一群。

### 時間基準（極重要）

**所有時間窗一律以事件的 `occurred_at` 計算，不是 `received_at`。**
批次匯入歷史資料時若用寫入時間，受眾會全錯。見 `01-event-contract.md` §6。

---

## 6. 排除條件

排除**獨立於條件樹**，不要逼行銷人員用 `NOT` 去想。

```json
{
  "tree": { ... },
  "exclusions": [
    { "type": "segment", "segment_id": "01JCXYZBLACKLIST..." },
    { "type": "segment", "segment_id": "01JCXYZCONTROL..." }
  ]
}
```

排除黑名單、排除已參加過的人、排除對照組是最高頻的操作，值得專門的位置。

---

## 7. 受眾包

```java
record Segment(
    String segmentId, String name, String description,
    SegmentType type,           // DYNAMIC / STATIC
    int version,                // 條件變更 → 新版本
    ConditionTree tree,         // DYNAMIC 用
    String snapshotRef,         // STATIC 用，指向名單
    Set<String> dependsOnFields,
    Set<String> dependsOnSegments,
    SegmentStatus status,
    Instant createdAt, String createdBy
) {}
```

### 動態 vs 靜態

| | 動態 | 靜態 |
|---|---|---|
| 存什麼 | 條件 | 一份名單快照 |
| 成員 | 隨資料變動 | 固定 |
| 適合 | 常態經營、旅程觸發 | 檔期活動、A/B 對照組、外部匯入 |

**動態受眾必須能一鍵轉存成靜態快照**——複盤「當時到底發給誰」一定會用到。

### 版本化

條件變更 → 新版本。`AudienceSourceNode` 綁定版本，避免「昨天圈的人今天不算數」。

### 循環引用

`dependsOnSegments` 在儲存時做**有向圖環偵測**，發現循環直接拒絕儲存。

---

## 8. 求值模式

```java
public interface SegmentEngine {
    boolean isMember(String profileId, String segmentId);           // 即時
    Map<String,Boolean> isMemberBatch(String profileId, Set<String> ids);
    Object evaluate(String profileId, BehaviorQuery query);
    Stream<String> materialize(String segmentId);                    // 批次
    long estimateCount(ConditionTree tree);                          // 預估
}
```

| 模式 | 用在哪 | 效能要求 |
|---|---|---|
| 即時判定 | `ConditionNode` / `FilterNode` | p99 < 50ms |
| 批次物化 | `AudienceSourceNode` 整包推入 | 可非同步 |
| 人數預估 | 篩選器 UI | p99 < 2s，**允許抽樣誤差** |

### 「從未做過」的效能

`occurrence = NEVER` 是反聯結（anti-join），資料量大時很慢。

**Phase 3 要求**：能力必須有。實作可先用 `NOT EXISTS`，效能不足時改為預先計算「每個客戶每種事件的最後發生時間」的彙總表。
`LAST_OCCURRED` 也吃同一張表——兩個最貴的查詢共用一份彙總，划算。

---

## 9. 篩選器 UI 的三個必要功能

缺一不可，否則行銷人員不敢自己圈：

| 功能 | 要求 |
|---|---|
| **即時人數預估** | 每改一個條件就更新。圈出 8 個或 80 萬都是圈錯了，要當下知道 |
| **樣本檢視** | 看 20 筆實際命中的客戶（依敏感等級遮罩）。**這是唯一能驗證「有沒有圈對人」的方法** |
| **條件白話化** | 把條件樹翻成一句人話，給主管審核 |

白話化範例：

> 「會員等級是金卡或白金卡，且近一年消費滿 5 萬，且超過 90 天沒買，且近 30 天有看過商品或開過訊息，排除客訴黑名單」

---

## 10. 受眾範本庫

平台提供開箱即用的範本，讓行銷人員不必從空白畫面開始。

### 界線

> 範本回答「**一般人會怎麼圈**」，不回答「**這個租戶的沉睡客戶是誰**」。
> 前者是行業知識，後者是決策——決策永遠留給行銷人員。

### 四條規則

```java
record SegmentTemplate(
    String templateId,
    boolean builtIn,            // true = 平台內建（原以 tenantId = null 表示，D-063）
    String name, String category, String description,
    ConditionTree tree,                       // 含參數佔位符
    List<TemplateParameter> parameters,       // 規則 ④
    Set<String> requiredFields,               // 規則 ②
    Set<String> requiredEvents,
    boolean enabled
) {}

record TemplateParameter(
    String key, String displayName, String question,
    DataType dataType, Object suggestedValue, boolean required
) {}
```

| 規則 | 說明 |
|---|---|
| ① 範本是資料 | 與自建受眾同一張表，只多一個標記。可停用、可修改，租戶能自建範本 |
| ② 宣告依賴 | 租戶缺欄位時顯示「此範本需要 訂單事件」，**而不是靜默算出空集合** |
| ③ 套用即複製 | 產生自己的副本。之後改副本不影響範本，範本更新也不影響已建立的受眾 |
| ④ **數字參數化** | 門檻值是**套用時要填的參數**，附建議值 |

規則 ④ 最容易漏但最關鍵：**「幾天算沉睡」對美妝和家電完全不是同一個數字**。
範本應該問「貴品牌幾天沒買算沉睡？」並給建議值，而不是替客戶決定。

### 首波範本

| 類別 | 範本 | 參數 |
|---|---|---|
| **新客與轉換** | 註冊未購買 | — |
| | 註冊滿 N 天未購買 | N=7 |
| | 首購客戶 | — |
| | 棄單未結帳 | N=7 |
| **活躍與互動** | 近期活躍 | N=30 |
| | 高互動未轉換 | N=30, M=5 |
| | 訊息高反應者 | N=90, M=3 |
| | **訊息零反應者** | N=90 |
| **流失與挽回** | 即將流失 | N₁=30, N₂=60 |
| | 沉睡客戶 | N=90 |
| | 流失客戶 | N=180 |
| | 回購週期到了 | 依品類 |
| **價值分層** | 高消費客戶 | X |
| | 高頻客戶 | N |
| | 高單價客戶 | X |
| | VIP | X, N |
| **生命週期** | 生日將至 | N=7 |
| | 會員週年 | N=1 |

> 「**訊息零反應者**」是用來**降低發送頻率**的受眾，不是加碼推播的。願意主動少發，是平台成熟度的指標。

### 自我檢驗

> **把整個範本庫刪光，平台功能不應有任何損失**——只是行銷人員要多花時間自己組條件。
> 若刪掉範本會壞掉任何功能，代表某個範本被寫成了系統行為，那就是設計出錯了。

---

## 11. 完整範例：高價值但即將流失

```json
{
  "segment_id": "01JCXYZSEGMENT000000001",
  "name": "高價值但即將流失",
  "type": "DYNAMIC",
  "version": 1,
  "tree": {
    "op": "AND",
    "children": [
      { "type": "attribute", "field": "member_tier",
        "operator": "IN", "value": ["GOLD", "PLATINUM"] },

      { "type": "behavior", "event": "order_completed",
        "occurrence": "AGGREGATE",
        "aggregation": { "function": "SUM", "field": "amount" },
        "operator": "GTE", "value": 50000,
        "time_window": { "kind": "RELATIVE", "last": "P365D" } },

      { "type": "behavior", "event": "order_completed",
        "occurrence": "LAST_OCCURRED",
        "operator": "MORE_THAN_AGO", "value": "P90D" },

      { "op": "OR", "children": [
        { "type": "behavior", "event": "product_viewed",
          "occurrence": "COUNT", "operator": "GTE", "value": 3,
          "time_window": { "kind": "RELATIVE", "last": "P30D" } },
        { "type": "behavior", "event": "message_opened",
          "occurrence": "COUNT", "operator": "GTE", "value": 1,
          "time_window": { "kind": "RELATIVE", "last": "P30D" } }
      ]}
    ]
  },
  "exclusions": [
    { "type": "segment", "segment_id": "01JCXYZBLACKLIST0000001" }
  ]
}
```

第 3 與第 4 個條件的組合就是「**他還沒死心（還在看），但已經很久沒買**」——這種洞察只有巢狀分組表達得出來，一層平鋪的 AND 做不到。

---

## 12. 驗收條件

- [ ] 程式碼中不存在任何具體業務條件（無 `isVIP()`、無 `getDormantCustomers()`）
- [ ] **新增一個資料欄位後，篩選器自動出現該選項，全程不改 code**
- [ ] 運算子清單依欄位型別動態產生，非寫死
- [ ] 上述巢狀範例能正確求值並回傳人數
- [ ] `LAST_OCCURRED` + `MORE_THAN_AGO` 能圈出沉睡客戶
- [ ] `ProfileRelative` 能圈出「註冊滿 30 天還沒下單的人」
- [ ] 受眾 A 引用 B、B 引用 A 時，儲存被拒絕
- [ ] `PII` 欄位在樣本檢視中依角色遮罩
- [ ] 缺少依賴欄位的範本，顯示「需要 X 欄位」而非空集合
- [ ] 套用範本時提示填寫參數並附建議值
- [ ] **刪光範本庫，平台功能不受損**
- [ ] 時間窗以 `occurred_at` 計算（匯入歷史資料後受眾仍正確）
