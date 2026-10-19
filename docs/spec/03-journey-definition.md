# 03 — 旅程定義（Journey Definition）

> 對應補強項 **G6**（旅程優先級）。
> 旅程定義是**資料，不是程式碼**——行銷人員在畫布上畫出來的東西序列化成 JSON。
> 這一點決定了：換 Temporal 時不會遇到 workflow versioning 問題（見計劃書 7.3）。

---

## 1. 版本化規則

| 規則 | 說明 |
|---|---|
| 定義不可變 | 已發布的版本**永不修改**。改動 → 產生新版本 |
| 建立時綁定 | Enrollment 建立時記下 `journeyVersion`，終生依該版執行 |
| 草稿不佔版本 | `DRAFT` 狀態可任意改，發布時才配版本號 |
| 版本單調遞增 | `v1`, `v2`, `v3`…，不重用 |

> 這是「五萬人卡在等待節點時旅程被改」的答案：**他們繼續跑舊版**，且系統答得出來為什麼。

---

## 2. 頂層結構

```json
{
  "journey_id": "01JCXYZJOURNEY0000000001",
  "tenant_id":  "01JCXYZ0000TENANT0000000001",
  "version":    3,
  "name":       "棄單挽回",
  "description": "加入購物車未結帳的挽回流程",
  "status":     "ACTIVE",
  "settings":   { ... },
  "entry_node": "start_1",
  "nodes":      [ ... ]
}
```

### `status`

`DRAFT` → `TESTING` → `PENDING_APPROVAL` → `ACTIVE` → (`PAUSED` ⇄ `ACTIVE`) → `ARCHIVED`

外加 `ABORTED`（緊急停止，見 `05-delivery-pipeline.md` G10）。

---

## 3. `settings`（旅程層設定）

**這些不是節點。** 它們作用於整條旅程或跨旅程，畫在畫布上會讓人誤以為只影響某一段。

```json
"settings": {
  "priority": 700,

  "reentry": {
    "mode": "DEDUP_KEY_WITH_COOLDOWN",
    "dedup_key_path": "$.trigger.properties.cart_id",
    "cooldown": "P7D"
  },

  "global_exit": {
    "conditions": [
      { "type": "event", "event": "order_completed", "since": "ENROLLMENT" },
      { "type": "attribute", "field": "marketing_opt_out", "operator": "EQ", "value": true }
    ],
    "on_match": "GOAL_MET"
  },

  "goal": {
    "goal_id": "purchase",
    "condition": { "type": "event", "event": "order_completed" },
    "window": "P7D"
  },

  "on_pause": "FREEZE",

  "budget": {
    "max_messages": 50000,
    "max_cost": 30000,
    "currency": "TWD",
    "on_exceed": "STOP_AND_ALERT"
  }
}
```

### `priority`（G6）

| 項目 | 定義 |
|---|---|
| 範圍 | `0` ~ `1000`，**數字大者優先** |
| 預設 | `500` |
| 用途 | 頻率上限／靜音時段必須擋人時，決定留誰擋誰 |

**建議分級**（僅為建議，租戶可自訂）：

| 區間 | 用途 |
|---|---|
| 900–1000 | 交易型通知（實務上不受頻率上限，此值僅供排序） |
| 700–899 | 高價值挽回（棄單、續約到期） |
| 400–699 | 一般生命週期經營 |
| 100–399 | 促銷、活動 |
| 0–99 | 內容型、可有可無 |

> 同優先級時，以 **Enrollment 建立時間較早者勝**（先到先贏），確保結果可預期、可重現。

### `reentry`

見 `02-enrollment-model.md` §6。`mode` 五選一：
`ONCE` / `DEDUP_KEY` / `COOLDOWN` / `DEDUP_KEY_WITH_COOLDOWN` / `UNLIMITED`

### `global_exit`

**在任何節點、任何狀態下持續生效**，包含 `WAITING` 中的實例。

`on_match` 決定終態：`EXITED`（單純移出）或 `GOAL_MET`（視為達標）。
> 「已完成購買」在棄單旅程中應該是 `GOAL_MET`，不是 `EXITED`——否則成效報表會低估。

**頂層 `on_match` 是預設值，個別條件可以覆寫。** 一條旅程常常同時有兩種語意的退出條件：

```json
"global_exit": {
  "conditions": [
    { "type": "event", "event": "order_completed", "since": "ENROLLMENT", "on_match": "GOAL_MET" },
    { "type": "attribute", "field": "marketing_opt_out", "operator": "EQ", "value": true }
  ],
  "on_match": "EXITED"
}
```

上例中「已購買」算達標、「已退訂」只是移出。**若只有單一的頂層 `on_match`，這兩件事會被迫共用同一個終態，成效報表必錯一邊**——附錄 C 場景三（全域退出＝發生任何購買直接算達標、已退訂）就是這個情況。

`since` 指定條件的觀察起點：`ENROLLMENT`（進入旅程後）或 `ALWAYS`（不限時間）。

> **落地（v0.2.0 第二版）**：`event` 條件在事件派發時對該客戶未結束的 Enrollment 求值（`occurred_at` ≥ `enrolled_at`）；`attribute` 條件在推進迴圈步驟 3 求值（每次建立、喚醒、連續推進），**不在**客戶屬性被寫入的當下求值——屬性變更要等該 Enrollment 下一次被推進才會退出，即時評估留到受眾版本（v0.4.0 以後）。`ALWAYS` 本版不支援（驗證錯誤）。

### `goal`

**`settings.goal` 是持續評估的，優先於一切節點。**

引擎在推進迴圈的每一次（`04-node-contract.md` §6 步驟 3）都會檢查它，與 `global_exit` 在同一個檢查點。命中即轉 `GOAL_MET`、取消計時器與待送訊息——**不論客戶當下在哪個節點、是不是還卡在 `WAITING`**。

| 欄位 | 語意 |
|---|---|
| `goal_id` | 供漏斗分析辨識 |
| `condition` | 達標條件 |
| `window` | **進入旅程後多久內達標才算數**。超過即不再判定，客戶繼續走完流程 |

`GoalNode` 的角色因此縮小為：**在畫布上顯式標記「這條路徑是成功終點」**。它不是達標的唯一途徑，也不是必要的——一條旅程只要設了 `settings.goal`，即使圖上沒有任何 `GoalNode`，客戶一樣會達標退出。

> **不要在旅程裡放一個「他已經做了嗎？」的 `ConditionNode` 來偵測達標。**
> `settings.goal` 已經在每次推進時檢查了，這個節點永遠等不到 `yes`——因為命中的瞬間 Enrollment 就已經轉終態，根本走不到它。
> 這是最容易寫出來的死碼，見 §7 範例的說明。

### `on_pause`

| 值 | 語意 |
|---|---|
| `FREEZE` | 進行中的人全部轉 `SUSPENDED`，恢復後從原處繼續 |
| `DRAIN` | 只擋新人進入，既有的人照常走完 |

### `budget`

**只計入、也只擋下「行銷型」訊息。** 交易型不受預算約束——理由見 `05-delivery-pipeline.md` §2「為什麼預算上限也不套用交易型」。

`on_exceed`：`STOP_AND_ALERT`（停止行銷型發送並告警）／`ALERT_ONLY`（僅告警，繼續發）。

---

## 4. 節點結構

```json
{
  "id": "wait_1",
  "type": "WaitDelayNode",
  "name": "等 1 小時",
  "config": { "duration": "PT1H" },
  "outputs": { "next": "cond_1" }
}
```

| 欄位 | 說明 |
|---|---|
| `id` | 旅程內唯一，`snake_case` |
| `type` | 節點型別，`PascalCase`，與 Java 類別同名 |
| `name` | 行銷人員自訂的顯示名稱 |
| `config` | 型別專屬設定，schema 依 `type` 而定 |
| `outputs` | **出口名 → 目標節點 id**。終端節點為 `{}` |

### `outputs` 的值型別

值為 `string`，**唯一例外是 `StartNode`，允許 `string[]`**：

```json
{ "id": "start_1", "type": "StartNode", "config": {},
  "outputs": { "next": ["src_event", "src_audience", "src_manual"] } }
```

計劃書附錄 A 的設計取捨要求「`StartNode` 之下可掛多個 Source 節點，代表多來源匯入同一旅程」——一個 port 只能指向一個節點的話，這件事在 JSON 上根本寫不出來。

**其他節點一律禁止陣列。** 多出口同時成立會讓一個 Enrollment 分裂成多條並行執行緒，那是完全不同等級的複雜度（狀態機、冪等鍵、終態判定全部要重做），本版不開。`StartNode` 之所以安全，是因為它不推進任何 Enrollment——多個 Source 各自獨立建立 Enrollment，彼此不共用狀態。

---

## 5. 節點型別與 config

### 入口 / 來源

#### `StartNode`
```json
{ "id": "start_1", "type": "StartNode", "config": {}, "outputs": { "next": "src_1" } }
```
圖的唯一根。不做任何事，是統計錨點與 `settings` 的掛載位置。
多來源時 `next` 可為陣列（見 §4「`outputs` 的值型別」），這是全格式中唯一允許陣列的位置。

#### `EventSourceNode`
```json
"config": {
  "event_name": "cart_item_added",
  "event_filter": { "cart_total": { "operator": "GT", "value": 500 } },
  "profile_filter": null
}
```
`profile_filter` 為 Segment 條件樹（見 `06-segment-dsl.md`），可為 null。

#### `AudienceSourceNode`
```json
"config": {
  "segment_id": "01JCXYZSEGMENT000000001",
  "mode": "ON_ENTER",          // SNAPSHOT / ON_ENTER / ON_EXIT
  "debounce": "P1D"            // 抖動抑制，見下
}
```
`debounce`：客戶在此期間內反覆進出受眾時，只觸發一次。

#### `ScheduleSourceNode`
```json
"config": {
  "cron": "0 0 3 * * *",
  "condition": { ... },
  "timezone_mode": "PROFILE_LOCAL"   // PLATFORM / PROFILE_LOCAL
}
```

#### `ManualSourceNode`
```json
"config": { "allow_api": true, "allow_import": true }
```

---

### 等待

#### `WaitDelayNode`
```json
"config": { "duration": "P3D" }
"outputs": { "next": "..." }
```

#### `WaitUntilNode`
```json
"config": {
  "target": { "kind": "DAILY_TIME", "time": "10:00" },
  "timezone_mode": "PROFILE_LOCAL"
}
```
`target.kind` 四選一，各自的欄位：

| `kind` | 必填欄位 | 例子 |
|---|---|---|
| `ABSOLUTE` | `at`（ISO-8601 date-time） | `{ "kind": "ABSOLUTE", "at": "2026-11-11T02:00:00Z" }` |
| `DAILY_TIME` | `time`（`HH:mm`） | `{ "kind": "DAILY_TIME", "time": "10:00" }` |
| `WEEKLY` | `day_of_week`、`time` | `{ "kind": "WEEKLY", "day_of_week": "FRIDAY", "time": "09:00" }` |
| `DYNAMIC` | `anchor`（`offset`＋`direction` 成對選填、`time` 選填） | `{ "kind": "DYNAMIC", "anchor": "$.variables.coupon_expires_at", "offset": "P2D", "direction": "BEFORE" }` |

`DYNAMIC` 的三個選填欄位：省略 `offset`／`direction` 即為錨點當下；`time` 用於「**錨點那一天的某個時刻**」——「生日當天上午 10:00」就是 `anchor` 指到生日、`time` 給 `"10:00"`，少了它這種需求得拆成兩個節點。

`DYNAMIC` 是 **G7（Phase 4）**：相對於旅程變數的動態時間（「券到期前 2 天」）。
欄位命名刻意對齊 `06-segment-dsl.md` 的 `ProfileRelative(anchorField, offset, direction)`——**同一個概念在兩處用同一組名字**，`direction` 同為 `BEFORE` / `AFTER`。`offset` 一律為正值，方向由 `direction` 表達，不用負號 duration。

#### `WaitForEventNode`
```json
"config": {
  "event_name": "order_completed",
  "event_filter": null,
  "timeout": "P1D"
}
"outputs": { "occurred": "goal_1", "timeout": "split_1" }
```
**雙出口**。`timeout` 為 null 表示無限等待（需搭配 `global_exit` 才安全）。

---

### 分流

#### `ConditionNode`
```json
"config": { "condition": { ...Segment 條件樹... } }
"outputs": { "yes": "...", "no": "..." }
```

#### `SwitchNode`
```json
"config": {
  "field": "member_tier",
  "cases": [
    { "value": "PLATINUM", "port": "platinum" },
    { "value": "GOLD",     "port": "gold" }
  ]
}
"outputs": { "platinum": "...", "gold": "...", "default": "..." }
```
**`default` 出口為必填**——處理沒填、沒值、不在清單內的人。

#### `SplitTestNode`
```json
"config": {
  "variants": [
    { "port": "a",       "weight": 45, "label": "發折價券" },
    { "port": "b",       "weight": 45, "label": "不發券" },
    { "port": "control", "weight": 10, "label": "對照組", "is_control": true }
  ],
  "assignment_seed": "STABLE"
}
```
規則：
- `weight` 總和必須 = 100
- **分組結果寫入 `variables.__split_{nodeId}`，重跑不得改變**
- 至多一個 `is_control: true`
- `STABLE`（唯一支援的 `assignment_seed`）：`variables` 已有分組就沿用；否則 `bucket = SHA-256(journey_id + ":" + node_id + ":" + profile_id)` 前 8 位元組（無號）mod 100，依 `variants` 陣列順序的累積權重取第一個 `bucket < 累積值` 者。同一客戶重新進入同一旅程會分到同一組（`docs/design/DESIGN-03.md`）

#### `FilterNode`
```json
"config": { "condition": { ... }, "exit_reason": "已購買，不需挽回" }
"outputs": { "pass": "..." }
```
不符合者直接轉 `EXITED`，`terminationReason` 取 `exit_reason`。
**`exit_reason` 必填**，與 `ExitNode.reason` 同理：退出原因是診斷「受眾是不是圈錯了」的唯一資料來源，留空等於讓那批人憑空消失。

#### `MergeNode`
```json
"config": {}
"outputs": { "next": "..." }
```

---

### 發送

#### `SendMessageNode`
```json
"config": {
  "template_id": "01JCXYZTEMPLATE00000001",
  "channel_id":  "01JCXYZCHANNEL000000001",
  "variables":   { "discount_code": "$.variables.coupon_code" },
  "on_skip":     "CONTINUE"
}
"outputs": { "sent": "...", "skipped": "..." }
```

| 項目 | 說明 |
|---|---|
| `channel_type` | **不在此指定**，由 `channel_id` 推導 |
| `message_type` | **不在此指定**，綁在 `template_id` 上（G9，見 README 裁決 1） |
| `on_skip` | `CONTINUE`（走 `skipped` 出口）／`RETRY_NEXT_DAY`／`EXIT`。**省略時預設為 `CONTINUE`** |
| `skipped` 出口 | `on_skip = CONTINUE` 時**必填**（含省略而套用預設值的情況） |

> **預設值為什麼是 `CONTINUE`**：被守門擋下的人不該被當成「已觸達」，否則成效報表的分母是錯的。
> 預設走 `skipped` 出口，等於強迫旅程設計者面對「這批人怎麼辦」——這是三個選項中唯一不會靜默失真的。

> 訊息類型不放在節點上，是為了讓「把促銷標成交易型來繞過退訂」這件事必須經過模板審核流程。

---

### 動作

#### `UpdateProfileNode`
```json
"config": {
  "operations": [
    { "op": "SET",       "field": "last_winback_at", "value": "$.now" },
    { "op": "INCREMENT", "field": "winback_count",   "value": 1 },
    { "op": "TAG",       "value": "discount_insensitive" }
  ]
}
```
`op`：`SET` / `INCREMENT` / `TAG` / `UNTAG`

#### `AudienceMembershipNode`
```json
"config": { "segment_id": "...", "action": "REMOVE" }
```
目標必須是**靜態受眾**。對動態受眾操作 → 驗證失敗。

#### `WebhookNode`
```json
"config": {
  "url": "https://coupon.example.com/issue",
  "method": "POST",
  "payload": { "profile_id": "$.profile_id", "campaign": "winback" },
  "wait_for_response": true,
  "timeout": "PT10S",
  "response_mapping": { "coupon_code": "$.body.code" }
}
"outputs": { "success": "...", "failure": "..." }
```
`wait_for_response: true` 時 `failure` 出口必填。回應可寫入 `variables`。

#### `InternalNotifyNode`
```json
"config": {
  "recipients": [ { "type": "ROLE", "value": "SALES" } ],
  "message": "高價值客戶 {{profile.name}} 出現流失徵兆"
}
```

---

### 出口

| 節點 | config | outputs |
|---|---|---|
| `EndNode` | `{}` | `{}` |
| `ExitNode` | `{ "reason": "對照組" }` | `{}` |
| `GoalNode` | `{ "goal_id": "purchase" }` | `{}` |
| `GoToJourneyNode` | `{ "target_journey_id": "..." }` | `{}` |

---

## 6. 圖驗證規則

> **落地（`docs/design/DESIGN-03.md`）**：本版三層都由後端 Java 執行（Schema 層用 `com.networknt:json-schema-validator`，版本與不相容時的退路見 DESIGN-03 做法 #7）；草稿存檔只驗結構，`validate`／`publish` 跑全部適用規則。本版不檢查規則 10、11（無模板表）。畫布座標放在定義之外的 `layout`（`docs/contracts/journeys.md` §1），引擎不讀。

發布（`DRAFT` → `PENDING_APPROVAL`）前必須全數通過：

驗證分三層，各有各的執行時機：

| 層 | 誰執行 | 何時 | 特性 |
|---|---|---|---|
| **Schema** | `journey-definition.schema.json` | 存檔即可跑，前端畫布也能直接用 | 純結構，不需要任何外部狀態 |
| **Graph** | Java 圖驗證器 | 發布前 | 要跑圖演算法 |
| **Reference** | Java + 資料庫 | **發布時必須重驗** | 依賴資料庫當下狀態——草稿存檔時通過，不代表發布時還通過（模板可能已被封存） |

| # | 規則 | 驗證層 | 錯誤等級 |
|---|---|---|---|
| 1 | 恰有一個 `StartNode`，且為 `entry_node` | Schema（前半）+ Graph（後半） | ❌ |
| 2 | Source 節點只能直接接在 `StartNode` 之後 | Graph | ❌ |
| 3 | 所有 `outputs` 指向的節點 id 必須存在 | Graph | ❌ |
| 4 | 每個節點的必填出口都已連線 | **Schema** | ❌ |
| 5 | 無孤兒節點（從 `StartNode` 不可達） | Graph | ❌ |
| 6 | 所有路徑最終可達某個出口節點 | Graph | ❌ |
| 7 | **不得有不含等待節點的迴圈**（會造成無限迴圈） | Graph | ❌ |
| 8 | `SplitTestNode` 權重總和 = 100 | **Graph**（JSON Schema 無加總能力） | ❌ |
| 9 | `SwitchNode` 有 `default` 出口 | **Schema** | ❌ |
| 10 | `SendMessageNode` 引用的模板與通道存在、已核准、通道型別相容 | Reference | ❌ |
| 11 | 模板中引用的變數，在資料字典或旅程 context 中存在 | Reference | ❌ |
| 12 | 有 `WaitForEventNode` 且 `timeout` 為 null，但無 `global_exit` | Graph | ⚠️ 警告 |
| 13 | 旅程中無任何 `GoalNode` 且 `settings.goal` 未設定 | Graph | ⚠️ 警告 |
| 14 | **非 `StartNode` 的節點，`outputs` 的值必須是 string（不得為陣列）** | **Schema** | ❌ |
| 15 | `SplitTestNode` / `SwitchNode` 的 `outputs` 必須涵蓋每個 variant／case 的 port | Graph（跨欄位） | ❌ |

> **Schema 檔的落點**：`backend/src/main/resources/schema/journey-definition.schema.json`（JSON Schema 2020-12）。
> 本目錄是獨立的 private repo、schema 在主 repo，**兩邊改動必須成對**——新增節點型別時要同時改本節與該檔的 `$defs/nodeType` 與 `$defs/node.allOf`。
> 驗收測試在 `backend/src/test/java/com/cdp/journey/JourneyDefinitionSchemaTest.java`。

> 規則 7 是最重要的安全網：一個沒有等待節點的迴圈會讓引擎在毫秒內把客戶推進數百萬次。
> 檢測方式：對圖做強連通分量分析，任一含迴圈的分量中若無 `Wait*` 節點即違規。

---

## 7. 完整範例：棄單挽回（計劃書場景一）

> **這個範例刻意沒有「已結帳？」判斷節點。**
> `settings.goal` 是 `order_completed` 且持續評估，客戶在「等 1 小時」期間下單就已經轉 `GOAL_MET` 退出旅程了——
> 一個放在等待之後的「已結帳？」`ConditionNode` 永遠等不到 `yes`，那是死碼。判斷交給 `settings.goal`，圖上就少一個節點。

```json
{
  "journey_id": "01JCXYZJOURNEY0000000001",
  "tenant_id":  "01JCXYZ0000TENANT0000000001",
  "version": 1,
  "name": "棄單挽回",
  "status": "ACTIVE",
  "entry_node": "start_1",

  "settings": {
    "priority": 750,
    "reentry": {
      "mode": "DEDUP_KEY_WITH_COOLDOWN",
      "dedup_key_path": "$.trigger.properties.cart_id",
      "cooldown": "P7D"
    },
    "global_exit": {
      "conditions": [
        { "type": "attribute", "field": "marketing_opt_out", "operator": "EQ", "value": true }
      ],
      "on_match": "EXITED"
    },
    "goal": {
      "goal_id": "checkout",
      "condition": { "type": "event", "event": "order_completed" },
      "window": "P7D"
    },
    "on_pause": "DRAIN"
  },

  "nodes": [
    { "id": "start_1", "type": "StartNode", "config": {},
      "outputs": { "next": "src_1" } },

    { "id": "src_1", "type": "EventSourceNode", "name": "加入購物車",
      "config": {
        "event_name": "cart_item_added",
        "event_filter": { "cart_total": { "operator": "GT", "value": 500 } }
      },
      "outputs": { "next": "wait_1" } },

    { "id": "wait_1", "type": "WaitDelayNode", "name": "等 1 小時",
      "config": { "duration": "PT1H" },
      "outputs": { "next": "cond_vip" } },

    { "id": "cond_vip", "type": "ConditionNode", "name": "是 VIP？",
      "config": {
        "condition": {
          "type": "attribute", "field": "member_tier",
          "operator": "IN", "value": ["GOLD", "PLATINUM"]
        }
      },
      "outputs": { "yes": "send_vip", "no": "send_normal" } },

    { "id": "send_vip", "type": "SendMessageNode", "name": "LINE 專屬關心",
      "config": { "template_id": "tpl_vip_cart", "channel_id": "ch_line_main", "on_skip": "CONTINUE" },
      "outputs": { "sent": "merge_1", "skipped": "merge_1" } },

    { "id": "send_normal", "type": "SendMessageNode", "name": "Email 提醒",
      "config": { "template_id": "tpl_std_cart", "channel_id": "ch_email_main", "on_skip": "CONTINUE" },
      "outputs": { "sent": "merge_1", "skipped": "merge_1" } },

    { "id": "merge_1", "type": "MergeNode", "config": {},
      "outputs": { "next": "wait_order_1" } },

    { "id": "wait_order_1", "type": "WaitForEventNode", "name": "等結帳 24h",
      "config": { "event_name": "order_completed", "timeout": "P1D" },
      "outputs": { "occurred": "goal_1", "timeout": "split_1" } },

    { "id": "goal_1", "type": "GoalNode", "config": { "goal_id": "checkout" }, "outputs": {} },

    { "id": "split_1", "type": "SplitTestNode", "name": "發券 A/B",
      "config": {
        "variants": [
          { "port": "coupon",  "weight": 50, "label": "發折價券" },
          { "port": "control", "weight": 50, "label": "對照組", "is_control": true }
        ]
      },
      "outputs": { "coupon": "hook_coupon", "control": "exit_control" } },

    { "id": "exit_control", "type": "ExitNode",
      "config": { "reason": "對照組" }, "outputs": {} },

    { "id": "hook_coupon", "type": "WebhookNode", "name": "發券",
      "config": {
        "url": "https://coupon.example.com/issue", "method": "POST",
        "payload": { "profile_id": "$.profile_id" },
        "wait_for_response": true, "timeout": "PT10S",
        "response_mapping": { "coupon_code": "$.body.code" }
      },
      "outputs": { "success": "send_coupon", "failure": "end_1" } },

    { "id": "send_coupon", "type": "SendMessageNode", "name": "LINE 券已送達",
      "config": {
        "template_id": "tpl_coupon", "channel_id": "ch_line_main",
        "variables": { "coupon_code": "$.variables.coupon_code" },
        "on_skip": "CONTINUE"
      },
      "outputs": { "sent": "wait_order_2", "skipped": "wait_order_2" } },

    { "id": "wait_order_2", "type": "WaitForEventNode", "name": "等結帳 48h",
      "config": { "event_name": "order_completed", "timeout": "P2D" },
      "outputs": { "occurred": "goal_1", "timeout": "tag_1" } },

    { "id": "tag_1", "type": "UpdateProfileNode",
      "config": { "operations": [ { "op": "TAG", "value": "discount_insensitive" } ] },
      "outputs": { "next": "end_1" } },

    { "id": "end_1", "type": "EndNode", "config": {}, "outputs": {} }
  ]
}
```

---

## 8. 驗收條件

- [ ] 計劃書附錄 C 三個場景都能用本格式完整表達，不需額外欄位
- [ ] 上述範例通過全部 13 條圖驗證規則
- [ ] 含「兩個節點互指且無等待節點」的圖，被規則 7 擋下
- [ ] `SwitchNode` 缺 `default` 出口時發布失敗
- [ ] 旅程從 v1 改為 v2 發布後，v1 的定義仍可完整讀取
- [ ] `SplitTestNode` 同一客戶重複推進時，分組結果不變
- [ ] `StartNode` 掛三個 Source 節點的旅程能通過驗證並正常運作
- [ ] 非 `StartNode` 的節點 `outputs` 使用陣列時，發布被規則 14 擋下
