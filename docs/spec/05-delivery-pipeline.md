# 05 — 發送管線（Delivery Pipeline）

> 對應補強項 **G9**（訊息類型：交易型 / 行銷型）、**G10**（緊急停止取消點）、**G6**（優先級仲裁）。
> 這是整個平台**唯一不可逆**的部分。訊息發出去收不回來——管線的每個設計決定都應該從這句話出發。

---

## 1. 管線階段

節點只回傳 `Effect.SendMessage`（見 `04-node-contract.md`），**不認識任何通道**。
管線入口：引擎在 `StateStore.save` 的同一交易寫入 `effect_outbox` → 獨立 relay 取到期列、經 publisher confirm 推到 RabbitMQ → 冪等 consumer 執行下列階段（`docs/design/INIT-01.md`「RabbitMQ」；表見 `07` §10）。
從 Effect 到客戶收到訊息，中間有六個階段：

```
① 接收發送意圖  (consumer 自 RabbitMQ 收到 idempotencyKey，讀 outbox 列)
        ↓
② 解析          (模板 / 通道 / 收件人 / 訊息類型)
        ↓
③ 守門          (Consent → 頻率上限 → 靜音時段 → 預算)   ← G9 決定套用範圍
        ↓
④ 仲裁          (同一收件人多則競爭時，依 priority 決定)  ← G6
        ↓
⑤ 渲染          (模板 + 變數 → 實際內容)
        ↓
⑥ 送出          (ChannelAdapter → 外部服務商)
```

### 取消點（G10）

**每個階段之間都是取消點。** 這是緊急停止能生效的前提。

| 階段 | 可取消 | 說明 |
|---|---|---|
| ①→② | ✅ | outbox 與 `delivery_task` 標記 `CANCELLED` |
| ②→③ | ✅ | |
| ③→④ | ✅ | |
| ④→⑤ | ✅ | |
| ⑤→⑥ | ✅ | **最後一個取消點** |
| ⑥ 之後 | ❌ | 已交給服務商，**不可逆** |

> **實作要求**：階段 ⑥ 之前的每一步，都必須在執行前重新檢查 `killSwitch` 狀態。
> 若把整條管線寫成一個不可中斷的方法，G10 就永遠做不到——這正是「必須在管線設計時預留取消點」的意思。

---

## 2. G9 — 訊息類型

### 定義

```java
enum MessageType {
    TRANSACTIONAL,   // 交易型
    MARKETING        // 行銷型
}
```

### 守門套用範圍

| 守門 | `TRANSACTIONAL` | `MARKETING` |
|---|---|---|
| Consent（退訂） | ❌ 不套用 | ✅ 套用 |
| 頻率上限 | ❌ 不套用 | ✅ 套用 |
| 靜音時段 | ❌ 不套用 | ✅ 套用 |
| 預算上限 | ❌ 不套用 | ✅ 套用 |
| **緊急停止（G10）** | ✅ **套用** | ✅ 套用 |
| 通道層速率限制 | ✅ 套用 | ✅ 套用 |

> 交易型不受 Consent，是因為客戶有權收到自己的訂單確認與驗證碼。
> 但**緊急停止對兩者都生效**——模板寫錯時，交易型訊息一樣會造成傷害。

### 為什麼預算上限也不套用交易型

「客戶有權收到自己的訂單確認與驗證碼」這個權利，**不會因為租戶這個月的行銷預算燒完而消失**。預算超支時連驗證碼都發不出去，是客訴等級的事故，而且理由對客戶完全講不通——他並沒有參與那些燒掉預算的促銷。

交易型的成本失控改用另一條路處理：**租戶層配額告警（80% / 100%）+ 平台管理員手動介入**，而不是自動斷送。「自動斷送」與「自動告警」的差別，就是事故與工單的差別。

### 防濫用機制

訊息類型是**繞過退訂的權限**，因此必須難以取得：

| 規則 | 說明 |
|---|---|
| 綁在模板上 | **不在節點上**。節點只指定 `template_id` |
| 預設為 `MARKETING` | 新建模板一律行銷型 |
| 改為交易型需審核 | 且審核者必須與建立者不同人 |
| 變更留稽核 | 誰、何時、把哪個模板改成交易型 |
| 定期稽核報表 | 列出所有交易型模板供合規檢視 |

> 若讓行銷人員能在節點上直接選「交易型」，平台就成了合法的濫發工具。位置的選擇就是防線本身。

---

## 3. 三道守門

### ① Consent（同意狀態）

```java
record ConsentState(
    String profileId,
    String channelType,
    String messageCategory,     // PROMOTION / NEWSLETTER / PRODUCT_UPDATE ...
    ConsentStatus status,       // OPTED_IN / OPTED_OUT / UNKNOWN
    Instant updatedAt,
    String source               // 同意來源，供稽核
) {}
```

判定順序：

1. `MessageType = TRANSACTIONAL` → **直接通過**
2. 通道層退訂（整個通道都不要） → 擋
3. 類別層退訂（只是不要促銷） → 擋
4. `UNKNOWN` → **依租戶設定**（預設：Email 通過、LINE 通過、SMS 擋）

擋下時 `SkipReason = CONSENT`。

### ② 頻率上限（跨旅程）

```json
"frequency_cap": {
  "rules": [
    { "channel": "LINE",  "window": "P1D", "max": 1 },
    { "channel": "LINE",  "window": "P7D", "max": 3 },
    { "channel": "EMAIL", "window": "P1D", "max": 2 },
    { "channel": "*",     "window": "P1D", "max": 3 }
  ]
}
```

| 要點 | 說明 |
|---|---|
| 作用域 | **租戶層**，跨所有旅程。不是單一旅程內的節流 |
| 計數基準 | **入列即扣配額（reserve）**，不是等到 `SENT` 才計 |
| 儲存 | Redis 計數器，key 為 `{tenantId}:{profileId}:{channel}:{window}` |
| 一致性 | 以 Redis 為準；Redis 不可用時 **fail-closed（擋下）** |

> **fail-closed 是刻意的**：計數器掛掉時寧可少發，不可能因為基礎設施故障而對客戶連發十則。

擋下時 `SkipReason = FREQUENCY_CAP`。

#### 配額的三個動作：reserve / commit / release

**「只計 `SENT`」是錯的做法，必須避免。** 若計數器要等訊息真的送出才 +1，兩則訊息在同一秒進管線時，兩者在守門階段讀到的計數都還是 0 → 都放行 → 都送出 → `max: 1` 被突破。**這是每天都會發生的競態，不是尖峰才有的邊界情況。**

因此配額有三個動作：

| 動作 | 何時 | 作用 |
|---|---|---|
| **reserve** | 守門階段 ③，訊息進入管線時 | 原子性遞增並檢查上限。**扣不到配額不直接判 `FREQUENCY_CAP`，而是進入仲裁窗排隊**（見 §4） |
| **commit** | 階段 ⑥ 送出成功 | 保留額度轉為實際用量，不再遞增 |
| **release** | 訊息最終為 `SKIPPED` / `CANCELLED` / `FAILED` | 歸還額度，避免沒送出的訊息長期佔用名額 |

**reserve 必須以 Redis Lua 腳本執行**，把「讀計數 → 比對上限 → 遞增」包成單一原子操作。分成兩次 Redis 往返（先 `GET` 再 `INCR`）等於把競態原封不動搬到應用層。

每個保留額度帶 **TTL（取窗口長度）**，確保管線崩潰導致 release 沒跑到時，額度最終會自行過期釋放，不會永久卡住客戶的名額。

### ③ 靜音時段

```json
"quiet_hours": { "start": "22:00", "end": "08:00", "timezone_mode": "PROFILE_LOCAL" }
```

處理方式由 `SendMessageNode.on_skip` 決定：

| `on_skip` | 靜音時段行為 |
|---|---|
| `CONTINUE` | 走 `skipped` 出口，`SkipReason = QUIET_HOURS` |
| `RETRY_NEXT_DAY` | **延後到靜音結束後送出**，不算 skip |
| `EXIT` | Enrollment 轉 `EXITED` |

> `RETRY_NEXT_DAY` 是靜音時段最常用的選項——訊息本身沒問題，只是時機不對。

---

## 4. G6 — 優先級仲裁

### 觸發時機：**只在配額不足時開窗**

仲裁窗**不是每則行銷訊息都要走的關卡**，而是「搶不到配額」時才啟動的排隊機制：

```
守門階段 ③ 對訊息執行 reserve（見 §3②）
   ├─ 扣得到配額 → 直接放行，零延遲，不進仲裁窗
   └─ 扣不到配額 → 不立刻判 FREQUENCY_CAP，改進入 5 分鐘仲裁窗排隊
```

**這是刻意的設計。** 若所有行銷訊息都無條件先壓 5 分鐘，等於為了 5% 會競爭的情況，讓 95% 不competing 的訊息一起付延遲代價——而這條管線的第一段就寫著「棄單後 30 分鐘推跟隔天推，轉換率不是同一個量級」。

### 規則

```
1. 訊息因 reserve 失敗進入該收件人的仲裁窗（預設 5 分鐘）
2. 窗內有訊息被 release（SKIPPED / CANCELLED / FAILED），或窗口時間滾動釋出額度時：
3. 依 journey.settings.priority 由高到低排序
4. 同優先級 → Enrollment 的 enrolledAt 較早者勝
5. 仍相同 → enrollmentId 字典序（確保結果可重現）
6. 依可用額度由前往後放行
7. 窗結束仍未獲放行者 → SkipReason = PRIORITY_LOST
```

### 為什麼一定要有窗

**問題不在「怎麼比優先級」，在「什麼時候你才有兩則訊息可以比」。**

| 時間 | 沒有窗會發生什麼 |
|---|---|
| 09:00:00.000 | 促銷（`priority: 200`）進管線。**此刻只有它一則，沒有比較對象** |
| 09:00:00.000 | 配額還剩 1 → 通過 → 送出 → 配額歸零 |
| 09:00:00.200 | 棄單挽回（`priority: 750`）進管線 → 配額剩 0 → 擋下 |

**優先級 750 輸給 200，只因為晚了 200 毫秒**——而系統在第一則進來時做的決定完全合理，它看不到未來。窗的作用就是製造一個「同時看得到兩則」的時刻。

**代價**：只有搶不到配額的訊息會延遲，最多 5 分鐘。配額充足時零延遲。交易型完全不進仲裁窗，**立即送出**。

---

## 5. G10 — 緊急停止（Kill Switch）

### 三個層級

| 層級 | 範圍 | 誰能操作 |
|---|---|---|
| **旅程層** | 中止該旅程所有進行中 Enrollment + 取消所有待送訊息 | 行銷主管以上 |
| **通道層** | 停掉該租戶某個通道的所有發送 | 租戶管理員 |
| **租戶層** | 停掉該租戶**所有**發送 | 租戶管理員 / 平台管理員 |

### 執行動作

```java
void killJourney(String tenantId, String journeyId, String reason) {
    1. journey.status = ABORTED                    // 立刻擋下新進入
    2. killSwitch.set(tenantId, journeyId)         // Redis，管線每階段都會讀
    3. effectExecutor.cancelPendingByJourney(...)  // 取消 outbox 中待送訊息
    4. timer.cancelAll(tenantId, journeyId)        // 取消所有計時器
    5. stateStore 批次轉 ABORTED，記 terminationReason
    6. 寫稽核日誌
}
```

**步驟順序不可調換**：先設 killSwitch 再取消 outbox。反過來會有空窗期讓訊息溜出去。

### 判定來源有兩層，Redis 不是唯一真相

管線每個階段開始前都要重讀 kill switch，判定來源依序：

1. **Redis**（`killSwitch:{tenantId}:{journeyId}`）——快取，正常路徑，微秒級
2. **Postgres**（`journey.status = ABORTED`、租戶停用旗標）——**Redis 讀取失敗時降級查它，不是直接放行**

> **為什麼不能只有 Redis**：頻率上限在 Redis 不可用時是 fail-closed（擋下行銷型），但**交易型不受頻率上限約束、會繼續送**。
> 若 kill switch 也只看 Redis，Redis 掛掉的那段時間交易型就止不了血——而「模板寫錯」與「基礎設施故障」常常同源（同一次搞砸的部署）。
> 步驟 1 本來就已經把 `journey.status` 寫進 Postgres 了，降級查它幾乎零成本。

| Redis 狀態 | 行銷型 | 交易型 |
|---|---|---|
| 正常 | 依 Redis 判定 | 依 Redis 判定 |
| 不可用 | fail-closed 擋下（頻率上限已擋） | **改查 Postgres 判定 kill switch**，未命中才送出 |

### 事後可解釋性

被中止的 Enrollment 必須答得出：

- 停在哪個節點
- 已經發過哪些訊息（`deliveryHistory`）
- 有幾則訊息被取消（`SkipReason = KILL_SWITCH`）
- 誰在什麼時候按下停止、理由是什麼

### 恢復

**緊急停止不可恢復。** `ABORTED` 是終態。
要繼續經營這批客戶，須建立新旅程重新納入——這是刻意的設計：出事後應該重新檢視，而不是直接接著跑。

---

## 6. Delivery 狀態機

```
 PENDING ──► GATED ──► ARBITRATED ──► RENDERED ──► SENT ──► DELIVERED
    │          │            │             │          │          │
    │          │            │             │          │          ├─► OPENED
    │          │            │             │          │          ├─► CLICKED
    │          │            │             │          │          └─► BOUNCED
    │          │            │             │          └─► FAILED（服務商拒絕）
    └──────────┴────────────┴─────────────┴─► SKIPPED / CANCELLED
```

| 狀態 | 說明 |
|---|---|
| `PENDING` | 已從 outbox 取出 |
| `GATED` | 通過三道守門 |
| `ARBITRATED` | 通過優先級仲裁 |
| `RENDERED` | 內容已渲染 |
| `SENT` | 已交給服務商，取得 `providerMessageId` |
| `DELIVERED` / `OPENED` / `CLICKED` / `BOUNCED` | 服務商回調 |
| `SKIPPED` | 被守門或仲裁擋下，帶 `SkipReason` |
| `CANCELLED` | 被 G10 取消 |
| `FAILED` | 送出失敗且重試耗盡 |

---

## 7. `ChannelAdapter`

```java
public interface ChannelAdapter {

    String channelType();          // LINE / EMAIL / SMS / APP_PUSH

    /** 該通道支援的模板格式 */
    Set<ContentFormat> supportedFormats();

    /** 解析收件人；回傳 empty 表示此客戶在該通道沒有可用地址 */
    Optional<Recipient> resolveRecipient(String tenantId, String profileId);

    /** 實際送出。必須是冪等的：同一 idempotencyKey 重送不得產生兩則訊息 */
    SendResult send(SendRequest request);

    /** 把服務商回調正規化為平台的 DeliveryEvent */
    List<DeliveryEvent> normalizeCallback(RawCallback callback);

    /** 驗證回調來源（簽章）。回 false 則丟棄 */
    boolean verifyCallback(RawCallback callback);
}
```

```java
record SendRequest(
    String tenantId, String channelId, String idempotencyKey,
    Recipient recipient, RenderedContent content,
    MessageType messageType, Instant deadline
) {}

record SendResult(
    SendStatus status,          // ACCEPTED / REJECTED / RETRYABLE_ERROR
    String providerMessageId,
    String errorCode, String errorMessage
) {}
```

### 冪等要求

服務商多半不保證冪等，因此 Adapter 必須自己處理：

1. 送出前查 `delivery_task` 表，若該 `idempotencyKey` 已有 `providerMessageId` → 直接回傳既有結果
2. 送出後**立即**寫入 `providerMessageId`
3. 服務商支援冪等鍵時（如 SendGrid 的 `X-Message-Id`），一併帶上

### 重試策略

| `SendStatus` | 處理 |
|---|---|
| `ACCEPTED` | 完成 |
| `REJECTED` | **不重試**（收件人無效、內容違規）→ `FAILED` |
| `RETRYABLE_ERROR` | 指數退避重試，上限 5 次；耗盡 → `FAILED` |

重試前必須**重新檢查 killSwitch 與 deadline**。deadline 過期的訊息不再送出——三天前的限時活動提醒沒有意義。

---

## 8. 模板與渲染

### 模板結構

```java
record MessageTemplate(
    String templateId, String tenantId,
    String groupId,               // 訊息內容群組：同一則訊息的各通道版本
    String channelType,
    MessageType messageType,      // G9
    ContentFormat format,
    String content,               // Handlebars 模板
    Map<String,VariableSpec> variables,
    TemplateStatus status,        // DRAFT / PENDING_APPROVAL / APPROVED / ARCHIVED
    String approvedBy, Instant approvedAt
) {}

record VariableSpec(String source, String path, String defaultValue, boolean required) {}
```

`source`：`PROFILE` / `TRIGGER`（G1）/ `VARIABLES` / `SYSTEM`

### 🔴 渲染安全

**必須使用 logic-less 模板引擎（Handlebars.java 或 Mustache）。**

禁止使用 FreeMarker / Thymeleaf / Velocity / JSP 渲染使用者輸入的模板——它們支援方法呼叫，等同開放伺服器端模板注入（SSTI）。多租戶下，一個租戶就能打穿整台機器。

額外限制：

| 限制 | 說明 |
|---|---|
| 變數白名單 | 只允許 `VariableSpec` 中宣告的變數 |
| 禁用 helper | 不註冊任何自訂 helper，不開放 `lookup`、`with` 之外的內建 |
| 輸出跳脫 | HTML 通道強制跳脫；純文字通道去除控制字元 |
| 渲染逾時 | 200ms，超時視為模板錯誤 |
| 大小上限 | 渲染結果超過通道限制 → `FAILED`，不截斷後送出 |

### 缺值處理

**每個變數必須指定 `defaultValue`，儲存模板時強制檢查。**

「親愛的 `{{name}}` 您好」在 name 為空時變成「親愛的  您好」——這種訊息發出去就是品牌事故。

`required = true` 且值為空時 → **不發送**，`status = FAILED`，理由 `MISSING_REQUIRED_VARIABLE`。

---

## 9. 測試發送

| 規則 | 說明 |
|---|---|
| 只發給測試名單 | 租戶預先設定的內部 Profile |
| **不計入任何統計** | 不寫 `deliveryHistory`、不計頻率上限、不進成效報表 |
| 不受 Consent | 測試名單視為已同意 |
| 內容加標記 | 主旨或開頭加 `[TEST]` |
| 需權限 | 且測試發送同樣寫稽核 |

---

## 10. 驗收條件

- [ ] 已退訂客戶**仍收得到**交易型訊息，**收不到**行銷型訊息（G9）
- [ ] 節點定義中無法指定 `message_type`（型別上不存在該欄位）
- [ ] 把模板從行銷型改為交易型，需經與建立者不同的人審核，且留稽核紀錄
- [ ] 同一客戶同時命中兩條旅程且觸及頻率上限時，`priority` 高者送出、低者 `SkipReason = PRIORITY_LOST`（G6）
- [ ] 優先級相同時，`enrolledAt` 較早者勝，且重跑結果一致
- [ ] Redis 不可用時，行銷型訊息被擋下（fail-closed），交易型不受影響
- [ ] 上限 `max: 1` 時，兩則行銷型訊息**在同一毫秒**進入管線，只有一則送出、另一則 `FREQUENCY_CAP`
- [ ] 被 `SKIPPED` / `CANCELLED` 的訊息，其保留配額有被 release（同窗內下一則訊息仍可送出）
- [ ] 旅程預算耗盡後，行銷型停發但**交易型仍正常送出**
- [ ] 按下旅程層緊急停止後，**已入 outbox 但未送出**的訊息不再送出（G10）
- [ ] 緊急停止後可查出：哪些人停在哪個節點、幾則訊息被取消
- [ ] 同一 `idempotencyKey` 送兩次，服務商只收到一次
- [ ] 模板含 `{{#each}}` 以外的 helper 或方法呼叫時，儲存失敗
- [ ] 必填變數缺值時不發送，並記錄 `MISSING_REQUIRED_VARIABLE`
- [ ] 測試發送不影響頻率上限計數與成效報表
