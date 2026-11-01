# 01 — 事件契約（Event Contract）

> 對應補強項 **G2**：事件必須涵蓋三種來源。
> 這是 Ingestion 的**對外契約**，改動成本最高的一份規格。所有下游（去重、身分解析、旅程觸發、`WaitForEventNode`）都只認這份契約。

---

## 1. 為什麼要三種來源

只設計「客戶行為」會在兩個場景撞牆：

| 場景 | 需要的事件 | 來源 |
|---|---|---|
| 生日券快過期要提醒 | 「券已核銷」「券即將到期」 | **外部系統**（票券系統） |
| 高價值客戶轉人工，等業務回報 | 「業務已致電」 | **內部操作**（平台使用者） |

三類事件的**可信度、身分識別方式、稽核要求都不同**，但下游要能用同一套邏輯處理，所以統一契約、以 `source_type` 區分。

---

## 2. 三種來源

| `source_type` | 誰產生 | 例子 | 可信度 |
|---|---|---|---|
| `CUSTOMER_BEHAVIOR` | 網站 / APP / SDK | 下單、瀏覽、加入購物車、開啟訊息 | 需驗證身分，可能偽造 |
| `EXTERNAL_SYSTEM` | ERP / 票券 / 物流 / CRM | 券已核銷、出貨完成、退款成立 | 需簽章驗證，驗過即可信 |
| `INTERNAL_OPERATION` | 平台內部使用者 | 業務已致電、客服已結案、人工標記 | 已登入，**必須留稽核** |

---

## 3. 欄位定義

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `event_id` | ULID | ✅ | **由產生端提供**，用於去重。同 id 重送視為同一事件 |
| `source_type` | enum | ✅ | 見上表 |
| `source_id` | String | 條件 | 哪個 SDK／哪個外部系統／哪個使用者，見 §4 |
| `name` | String | ✅ | 事件名稱，`snake_case`，如 `order_completed` |
| `occurred_at` | Instant | ✅ | **事件實際發生時間**，非寫入時間。見 §6 |
| `received_at` | Instant | 系統填 | 平台接收時間，由 Ingestion 填入 |
| `identity` | Object | ✅ | 這件事是誰做的，見 §5 |
| `properties` | Object | — | 事件屬性，自由 key-value。**旅程可用它做條件與個人化** |
| `context` | Object | 條件 | 來源上下文，見 §4 |

### `identity`

```
identity: {
  profile_id?: ULID          // 已知客戶時直接給，優先採用
  identifiers?: [            // 未知時給識別碼，交由身分解析合併
    { type: "EMAIL" | "PHONE" | "LINE_USER_ID" | "MEMBER_ID" | "DEVICE_ID",
      value: String }
  ]
}
```

**規則**：`profile_id` 與 `identifiers` 至少擇一。兩者都給時以 `profile_id` 為準，但 `identifiers` 仍寫入身分解析供未來合併參考。

### `context`

```
context: {
  actor_id?: String        // INTERNAL_OPERATION 必填：誰操作的
  actor_role?: String      // 操作者角色，供稽核
  trust_level?: "VERIFIED" | "UNVERIFIED"
  ip?: String
  user_agent?: String
  request_id?: String      // 追蹤用
}
```

---

## 4. 各來源的差異規則

| | `CUSTOMER_BEHAVIOR` | `EXTERNAL_SYSTEM` | `INTERNAL_OPERATION` |
|---|---|---|---|
| `source_id` | 選填（SDK 版本等） | **必填**（系統代號） | **必填**（使用者 ID） |
| `context.actor_id` | 不適用 | 選填 | **必填** |
| 認證方式 | 公開 Write Key | **HMAC 簽章** | 平台 Session／API Token |
| `trust_level` | 依驗證結果 | 簽章通過 → `VERIFIED` | 恆為 `VERIFIED` |
| 稽核 | 不需 | 記錄來源系統 | **必須寫入稽核日誌** |
| 速率限制 | 每租戶每秒上限 | 較寬鬆 | 較嚴格（防誤操作） |

> ⚠️ **`INTERNAL_OPERATION` 事件會直接推進旅程**（例如讓客戶離開人工分支）。因此它必須留稽核——出事時要查得到是誰、什麼時候送的。

---

## 5. 身分解析的時機

Ingestion **不做**身分合併，只做兩件事：

1. 驗證 `identity` 至少有一個可用識別碼
2. 呼叫 `ProfileStore.resolve(identity)` 取得 `profileId`

解析不到時的處理，依 `source_type`：

| 來源 | 解析不到怎麼辦 |
|---|---|
| `CUSTOMER_BEHAVIOR` | **建立匿名 Profile**，之後有識別碼再合併 |
| `EXTERNAL_SYSTEM` | 進入**待處理佇列**並告警。外部系統應該認得客戶，解析不到代表資料有問題 |
| `INTERNAL_OPERATION` | **拒絕**並回傳錯誤。內部操作必須針對已知客戶 |

---

## 6. 時間語意（最容易出錯的地方）

**`occurred_at` 是事件發生時間，不是資料寫入時間。**

- 所有受眾條件的時間窗（「近 30 天」）一律以 `occurred_at` 計算
- 批次匯入歷史資料時，`occurred_at` 必須是原始發生時間。若填成匯入當下時間，**所有受眾會全錯**
- `received_at` 只用於監控延遲與除錯，**不得用於任何業務邏輯**

### 遲到事件（Late Arrival）

允許 `occurred_at` 早於 `received_at`（離線 APP 補傳、批次匯入）。

| 遲到幅度 | 處理 |
|---|---|
| ≤ 24 小時 | 正常處理，可觸發旅程 |
| > 24 小時 | 寫入資料層供受眾計算，**但不建立新 Enrollment** |
| > 保留期 | 拒絕 |

> 理由：對三天前的棄單發「你剛剛忘了結帳」是負面體驗。門檻值可依租戶設定，預設 24 小時。

### 遲到門檻**只約束「建立新 Enrollment」，不約束「喚醒既有 Enrollment」**

這兩件事的失敗方向剛好相反：

| | 建立新 Enrollment | 喚醒既有 Enrollment |
|---|---|---|
| 遲到事件照做的後果 | **多發一則不合時宜的訊息**（「你剛剛忘了結帳」但那是三天前） | **少發一則不該發的訊息**（客戶已核銷，就別再催他用券） |
| 因此遲到事件 | 擋下 | **照常喚醒** |

`WaitForEventNode` 只受自己的 `timeout` 約束，與遲到門檻無關：

- 事件的 `occurred_at` ≤ `timeoutAt` → 走 `occurred` 出口
- 事件的 `occurred_at` > `timeoutAt` → 走 `timeout` 出口（該逾時的還是逾時，不因補送而改變結果）

> **為什麼這條必須寫清楚**：通道回調延遲超過 24 小時並不罕見（Email 的 bounce 尤其），
> 而 §10 規定 DeliveryEvent 一律回流成事件。若遲到門檻連喚醒一起擋，
> 「已退訂」「已核銷」「已送達」這類補送的狀態就永遠進不了旅程，客戶會收到明知不該發的後續訊息。

### 未來時間

`occurred_at` 超前 `received_at` 5 分鐘以上 → **拒絕**（時鐘錯亂或偽造）。

---

## 7. 冪等與去重

- 去重鍵為 `event_id`（一套環境一家公司，不帶 `tenant_id`，D-063）
- 重複的 `event_id` 直接丟棄並回 `200`（不是錯誤，是正常重試）
- 去重視窗預設 **7 天**，超過視窗的重複視為新事件
- 產生端**必須**提供穩定的 `event_id`。同一筆業務動作重送時要用同一個 id

---

## 8. JSON 範例

### CUSTOMER_BEHAVIOR — 加入購物車

```json
{
  "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB",
  "source_type": "CUSTOMER_BEHAVIOR",
  "source_id": "web-sdk@2.1.0",
  "name": "cart_item_added",
  "occurred_at": "2026-08-21T09:15:32Z",
  "identity": {
    "identifiers": [
      { "type": "EMAIL", "value": "user@example.com" },
      { "type": "DEVICE_ID", "value": "dev_8f3a2b" }
    ]
  },
  "properties": {
    "cart_id": "CART-99201",
    "cart_total": 1580,
    "currency": "TWD",
    "items": [
      { "sku": "SKU-001", "name": "保濕精華液", "price": 980, "qty": 1 },
      { "sku": "SKU-042", "name": "洗面乳", "price": 600, "qty": 1 }
    ]
  },
  "context": { "trust_level": "VERIFIED", "ip": "203.0.113.5" }
}
```

> `properties.cart_id` 就是 G3 去重鍵的來源；`properties.items` 就是 G1 觸發快照要帶給訊息模板的內容。

### EXTERNAL_SYSTEM — 券已核銷

```json
{
  "event_id": "01JCXYZ9M4N5P6Q7R8S9T0ABCD",
  "source_type": "EXTERNAL_SYSTEM",
  "source_id": "coupon-service",
  "name": "coupon_redeemed",
  "occurred_at": "2026-08-21T10:02:11Z",
  "identity": { "profile_id": "01JCXYZPROFILE00000000001" },
  "properties": {
    "coupon_code": "BDAY500",
    "coupon_id": "CPN-7781",
    "discount_amount": 500,
    "order_id": "ORD-55012"
  },
  "context": { "trust_level": "VERIFIED" }
}
```

### INTERNAL_OPERATION — 業務已致電

```json
{
  "event_id": "01JCXYZA5N6P7Q8R9S0T1ABCDE",
  "source_type": "INTERNAL_OPERATION",
  "source_id": "user_01JCXYZSALES0000000000001",
  "name": "sales_contact_completed",
  "occurred_at": "2026-08-21T11:30:00Z",
  "identity": { "profile_id": "01JCXYZPROFILE00000000002" },
  "properties": {
    "outcome": "REACHED",
    "note": "客戶表示近期有採購需求",
    "next_action": "SEND_QUOTE"
  },
  "context": {
    "actor_id": "user_01JCXYZSALES0000000000001",
    "actor_role": "SALES",
    "trust_level": "VERIFIED"
  }
}
```

---

## 9. 攝取 API

```
POST /v1/events           # 單筆
POST /v1/events/batch     # 批次，上限 500 筆
```

**回應**

| 狀態 | 意義 |
|---|---|
| `202 Accepted` | 已接收（含重複事件被丟棄的情況） |
| `400` | Schema 驗證失敗，回傳逐欄錯誤 |
| `401` / `403` | 認證失敗、簽章不符 |
| `429` | 超過速率限制 |

批次回應逐筆回報結果，**部分成功不整批失敗**：

```json
{
  "accepted": 498,
  "rejected": 2,
  "results": [
    { "event_id": "01J...", "status": "ACCEPTED" },
    { "event_id": "01J...", "status": "DUPLICATE" },
    { "event_id": "01J...", "status": "REJECTED", "error": "identity: 至少需要 profile_id 或一組 identifiers" }
  ]
}
```

---

## 10. 回流事件（Analytics → Event）

DeliveryEvent（送達／開啟／點擊／失敗／退訂）**必須回流成標準事件**，讓「開了信但沒買 → 兩天後補推」這種旅程自然成立，不需特例處理。

回流事件以 `source_type = EXTERNAL_SYSTEM`、`source_id = channel-{通道}` 產生：

| DeliveryEvent | 回流事件名稱 |
|---|---|
| 送達 | `message_delivered` |
| 開啟 | `message_opened` |
| 點擊 | `message_clicked` |
| 失敗 | `message_failed` |
| 退訂 | `message_unsubscribed` |

`properties` 必帶：`journey_id`、`enrollment_id`、`node_id`、`template_id`、`channel_type`。

> 帶上這些才能讓 `WaitForEventNode` 精準等待「**這一則**訊息被開啟」，而不是「任何訊息被開啟」。

---

## 11. 驗收條件

**結構層**（`backend/src/main/resources/schema/event.schema.json`，測試在 `EventContractSchemaTest`）：

- [x] 三種 `source_type` 都能用同一份 schema 描述並通過驗證
- [x] `EXTERNAL_SYSTEM` 缺 `source_id`、`INTERNAL_OPERATION` 缺 `context.actor_id` 時被擋下
- [x] `identity` 的 `profile_id` 與 `identifiers` 兩者皆無時被擋下
- [x] 回流事件（`message_*`）缺任一必帶屬性時被擋下

**執行期**（Phase 1 之後隨 Ingestion 實作驗收）：

- [ ] 同一 `event_id` 重送 5 次，只產生 1 筆事件
- [ ] `occurred_at` 為 3 天前的事件，寫入資料層但不觸發旅程（但**仍可喚醒既有的 `WaitForEventNode`**，見未決事項 5）
- [ ] `INTERNAL_OPERATION` 事件在稽核日誌中可查到 `actor_id`
- [ ] `EXTERNAL_SYSTEM` 事件簽章錯誤時回 `403` 且不落地
- [ ] 一則訊息被開啟後，能回流成帶 `enrollment_id` 的 `message_opened` 事件

> **`CUSTOMER_BEHAVIOR` 不得帶 `context.actor_id` / `actor_role`**——§4 標為「不適用」，schema 直接擋下。
> 理由：客戶行為事件沒有操作者，出現 `actor_id` 幾乎都代表 `source_type` 標錯了，早點擋比事後查資料好。
