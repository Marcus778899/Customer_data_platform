# 契約：旅程引擎 v0.1.0 — 旅程建立、事件攝取、軌跡查詢（v1）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-01.md；欄位語意正本見 docs/spec/ -->

## §1 通用

- Base URL：前端一律用相對路徑 `/api`（dev server 轉給後端，見 `web/vite.config.ts`）；後端 port 為環境變數 `API_PORT`
- Auth：v0.1.0 無（見 DESIGN-01 待決定 1）；`tenant_id` 一律取環境變數 `CDP_TENANT_ID`，請求 body 帶 `tenant_id` 會被忽略
- 跨域：不開 CORS（同源經 proxy）
- JSON 欄位 `snake_case`；時間為 ISO-8601 UTC（`2026-10-18T02:00:00Z`）；期間為 ISO-8601 Duration（`PT1H`）；ID 為 ULID 字串
- 所有時間都取自後端注入的 `Clock`（含 `received_at`、`as_of`）：正式環境 = 系統時間；測試快轉時 = 模擬時間，同一筆回應不會混用兩種時間
- 每個回應都有標頭 `X-Request-Id`；請求帶合法的 `X-Request-Id`（1–64 字元 `[A-Za-z0-9._-]`）會沿用，否則後端產生

## §2 共用列舉

| 型別 | 值 |
|---|---|
| `EnrollmentStatus` | `PENDING` `ACTIVE` `WAITING` `SUSPENDED` `COMPLETED` `GOAL_MET` `EXITED` `FAILED` `ABORTED`（語意見 spec 02 §3；v0.1.0 實際會出現 `ACTIVE` `WAITING` `COMPLETED` `FAILED`） |
| `DeliveryStatus` | `PENDING`（已排入、尚未送） `SENT` `SKIPPED` `FAILED` `CANCELLED` |
| `SkipReason` | `CONSENT` `FREQUENCY_CAP` `QUIET_HOURS` `PRIORITY_LOST` `BUDGET_EXCEEDED` `KILL_SWITCH`（v0.1.0 無守門，不會出現） |
| `MessageType` | `TRANSACTIONAL` `MARKETING`；v0.1.0 無模板表，一律 `null` |
| 節點 `type` | v0.1.0 只接受 `StartNode` `EventSourceNode` `WaitDelayNode` `SendMessageNode` `EndNode` |

## §3 寫入 API

### §3.1 建立旅程版本 `POST /api/v1/journeys`

- Request：spec 03 §2 頂層結構去掉 `tenant_id`、`version`、`status`：`journey_id`（省略 = 新旅程，後端產生）、`name`（必填）、`description`、`settings`（物件，v0.1.0 不評估）、`entry_node`、`nodes[]`（`id` `type` `name` `config` `outputs`，見 spec 03 §4）
- 規則：`entry_node` 指向唯一的 `StartNode`；`StartNode.outputs.next` 指向 `EventSourceNode`（可為陣列）；每個 `outputs` 目標都存在；無迴圈；每條路徑終於 `EndNode`；`EventSourceNode.config.event_name` 必填、`event_filter`／`profile_filter` 須為 `null` 或省略；`WaitDelayNode.config.duration` 為正的 Duration；`SendMessageNode.config` 需 `template_id`、`channel_id`，`outputs` 需 `sent`（`skipped` 可省略）；`node.name` 可省略
- 成功 `201`：`{ "journey_id", "version", "status": "ACTIVE", "published_at" }`；同 `journey_id` 再送 = 新版本，進行中的 Enrollment 不受影響

### §3.2 事件攝取 `POST /api/v1/events`、`POST /api/v1/events/batch`

- Request（單筆）：spec 01 §3 欄位；v0.1.0 子集規則：`event_id` ULID 必填；`source_type` 必填；`source_id` 依 spec 01 §4；`name` 符合 `^[a-z][a-z0-9_]*$`；`occurred_at` 必填且不得超前伺服器時間 5 分鐘；`identity.profile_id` **必填**（v0.1.0 無身分解析，`identifiers` 可帶、只存不用）；`INTERNAL_OPERATION` 需 `context.actor_id`；`CUSTOMER_BEHAVIOR` 不得帶 `context.actor_id`／`actor_role`
- Request（批次）：`{ "events": [ … ] }`，1–500 筆
- 單筆回應：`202` `{ "event_id", "status": "ACCEPTED" | "DUPLICATE", "errors": [] }`；驗證失敗 `400`（§5，`code` = `VALIDATION_FAILED`）
- 批次回應：`202` `{ "accepted", "rejected", "results": [ { "event_id", "status", "errors": [ { "field", "message" } ] } ] }`，`accepted` 含 `DUPLICATE`，部分成功不整批失敗；超過 500 筆 `400` `BATCH_TOO_LARGE`
- 攝取只落地；Enrollment 由後端非同步建立（輪詢間隔 `ENGINE_POLL_INTERVAL`）。`occurred_at` 早於接收 24 小時以上：`ACCEPTED` 但不建立 Enrollment（spec 01 §6）

## §4 查詢 API（唯讀，D-005；前端依此 mock）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| GET | `/api/v1/enrollments` | `q`（必填，精確比對 `profile_id` 或 `enrollment_id`）、`status`（選填，逗號分隔多個 `EnrollmentStatus`）、`cursor`（選填）、`limit`（1–100，預設 20） | `200` `Page<EnrollmentSummary>`，依 `enrolled_at` 新到舊；查無 = `items: []`（不是 404） | `400` |
| GET | `/api/v1/enrollments/{enrollment_id}` | — | `200` `EnrollmentDetail` | `404` `ENROLLMENT_NOT_FOUND` |
| GET | `/api/v1/enrollments/{enrollment_id}/deliveries` | `cursor`、`limit`（1–100，預設 50） | `200` `Page<Delivery>`，依 `attempted_at` 新到舊 | `400`、`404` `ENROLLMENT_NOT_FOUND` |

### §4.1 資料模型

| 型別 | 欄位 | 型別 | 規則 |
|---|---|---|---|
| `Page<T>` | `items` | `T[]` | |
| | `next_cursor` | string \| null | 不透明字串，原樣帶回 `cursor`；`null` = 沒有下一頁 |
| `EnrollmentSummary` | `enrollment_id` `journey_id` `profile_id` `current_node_id` `current_node_type` | string | |
| | `journey_version` | int | 建立時綁定 |
| | `journey_name` | string | §4.2 |
| | `current_node_name` | string \| null | §4.2 |
| | `status` | `EnrollmentStatus` | |
| | `enrolled_at` `updated_at` | time | |
| | `terminated_at` | time \| null | 未結束為 `null` |
| `EnrollmentDetail` | （`EnrollmentSummary` 全部欄位） | | |
| | `termination_reason` | string \| null | 人可讀，規則同 §4.3 |
| | `wake_condition` | `WakeCondition` \| null | 僅 `WAITING` 有值 |
| | `variables` | object | 旅程變數，v0.1.0 為 `{}` |
| | `trigger_snapshot` | object | `event_id` `event_name` `occurred_at` `source_type` `properties`（事件屬性原樣）`profile_snapshot`（v0.1.0 一律 `{}`） |
| | `trace` | `TraceEntry[]` | 全部軌跡，依 `seq` 由舊到新 |
| | `delivery_history` | `Delivery[]` | 最新 50 筆，新到舊（Q6） |
| | `delivery_history_next_cursor` | string \| null | 更舊紀錄用 `/deliveries?cursor=` 續查 |
| | `as_of` | time | 產生此回應時的後端時間（Q4） |
| `WakeCondition` | `type` | `TIMER` \| `EVENT` \| `SIGNAL` | v0.1.0 只有 `TIMER` |
| | `wake_at` | time \| null | `TIMER`：預計喚醒時間；實際喚醒最多晚一個輪詢間隔 |
| | `event_name` `timeout_at` `signal_name` | | v0.2.0 起使用，v0.1.0 為 `null` |
| `TraceEntry` | `seq` | int | Enrollment 內遞增，從 1 開始 |
| | `node_id` `node_type` | string | `node_type` 為完整型別名，例 `WaitDelayNode` |
| | `node_name` | string \| null | §4.2 |
| | `entered_at` | time | |
| | `exited_at` | time \| null | 仍在此節點為 `null`（= 目前節點） |
| | `out_port` | string \| null | 出口名，v0.1.0：`next`、`sent`；仍在節點或終點為 `null` |
| | `decision_reason` | string | 必有，§4.3 |
| | `evaluated` | object | 判斷時實際取到的值，鍵 `snake_case`，可能為 `{}` |
| `Delivery` | `idempotency_key` | string | 這筆發送的唯一鍵 |
| | `node_id` `trace_seq` | string、int | 對應 `TraceEntry.node_id` 與 `seq`（Q3） |
| | `node_name` | string \| null | §4.2 |
| | `template_id` `channel_id` | string | 旅程定義裡的值；v0.1.0 無模板／通道名稱 |
| | `channel_type` | string | v0.1.0 一律 `MOCK` |
| | `message_type` | `MessageType` \| null | v0.1.0 為 `null` |
| | `status` | `DeliveryStatus` | |
| | `skip_reason` | `SkipReason` \| null | `SKIPPED` 時必有 |
| | `attempted_at` | time | |
| | `delivery_id` | string \| null | 服務商訊息 id，`SENT` 時有值 |

### §4.2 名稱來源（Q2）

`journey_name`、`node_name`、`current_node_name` 由後端從該 Enrollment **綁定版本**（`journey_version`）的旅程定義帶出（spec 03 §2 `name`、§4 `nodes[].name`）；之後改版不影響。`node.name` 未填時為 `null`，前端自行以 `node_type` 顯示。

### §4.3 `decision_reason` 與 `termination_reason`（Q5）

可直接給客服看：繁體中文、純文字（無 HTML／Markdown）、一句完整句、≤ 200 字；寫出實際判斷依據的值與時間（ISO-8601 UTC），不寫內部堆疊或 SQL。`decision_reason`、`evaluated`、`trigger_snapshot` 可能含事件屬性中的個資，遮罩規則見 DESIGN-01 待決定 2。

## §5 錯誤格式

`Content-Type: application/problem+json`（RFC 9457），HTTP status 區分 `400`／`404`／`5xx`，`code` 區分細項：

| 欄位 | 型別 | 規則 |
|---|---|---|
| `type` | string | 固定 `about:blank` |
| `title` | string | HTTP 狀態短語 |
| `status` | int | 同 HTTP status |
| `code` | string | `VALIDATION_FAILED` `BATCH_TOO_LARGE` `ENROLLMENT_NOT_FOUND` `JOURNEY_NOT_FOUND` `NOT_FOUND` `INTERNAL_ERROR` `SERVICE_UNAVAILABLE` |
| `detail` | string | 人可讀說明；5xx 不含內部訊息 |
| `request_id` | string | 同回應標頭 `X-Request-Id`，供回報 |
| `errors` | `{ field, message }[]` | 僅 `400`；`field` 為 JSON 路徑（`identity.profile_id`、`events[3].occurred_at`、`cursor`） |

`404` = 這筆資料不存在（不必重試）；`500` `INTERNAL_ERROR`、`503` `SERVICE_UNAVAILABLE`（資料庫連不上）= 可重試。

## §6 範例

`GET /api/v1/enrollments?q=01JCXYZPROFILE00000000001&status=WAITING,COMPLETED` → `200`

```json
{ "items": [ { "enrollment_id": "01JCXYZENROLL000000000001", "journey_id": "01JCXYZJOURNEY0000000001", "journey_version": 2,
  "journey_name": "棄單挽回", "profile_id": "01JCXYZPROFILE00000000001", "status": "WAITING",
  "current_node_id": "wait_1", "current_node_name": "等 3 天", "current_node_type": "WaitDelayNode",
  "enrolled_at": "2026-10-18T02:00:00Z", "updated_at": "2026-10-18T02:00:00Z", "terminated_at": null } ],
  "next_cursor": null }
```

`GET /api/v1/enrollments/01JCXYZENROLL000000000001` → `200`

```json
{ "enrollment_id": "01JCXYZENROLL000000000001", "journey_id": "01JCXYZJOURNEY0000000001", "journey_version": 2,
  "journey_name": "棄單挽回", "profile_id": "01JCXYZPROFILE00000000001", "status": "WAITING",
  "current_node_id": "wait_1", "current_node_name": "等 3 天", "current_node_type": "WaitDelayNode",
  "enrolled_at": "2026-10-18T02:00:00Z", "updated_at": "2026-10-18T02:00:00Z", "terminated_at": null,
  "termination_reason": null,
  "wake_condition": { "type": "TIMER", "wake_at": "2026-10-21T02:00:00Z", "event_name": null, "timeout_at": null, "signal_name": null },
  "variables": {},
  "trigger_snapshot": { "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB", "event_name": "cart_item_added", "occurred_at": "2026-10-18T01:59:41Z",
    "source_type": "CUSTOMER_BEHAVIOR", "properties": { "cart_id": "CART-99201", "cart_total": 1580 }, "profile_snapshot": {} },
  "trace": [
    { "seq": 1, "node_id": "src_1", "node_type": "EventSourceNode", "node_name": "加入購物車", "entered_at": "2026-10-18T02:00:00Z",
      "exited_at": "2026-10-18T02:00:00Z", "out_port": "next", "decision_reason": "收到事件 cart_item_added，建立 Enrollment。",
      "evaluated": { "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB" } },
    { "seq": 2, "node_id": "wait_1", "node_type": "WaitDelayNode", "node_name": "等 3 天", "entered_at": "2026-10-18T02:00:00Z",
      "exited_at": null, "out_port": null, "decision_reason": "等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。",
      "evaluated": { "duration": "P3D", "wake_at": "2026-10-21T02:00:00Z" } } ],
  "delivery_history": [], "delivery_history_next_cursor": null, "as_of": "2026-10-18T02:00:05Z" }
```

`GET /api/v1/enrollments/01JNOTEXIST000000000000000` → `404`

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "code": "ENROLLMENT_NOT_FOUND",
  "detail": "找不到這筆 Enrollment。", "request_id": "01JCXYZREQ0000000000000001" }
```

`POST /api/v1/events` body = spec 01 §8 第一個範例加上 `"identity": { "profile_id": "01JCXYZPROFILE00000000001" }` → `202` `{ "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB", "status": "ACCEPTED", "errors": [] }`
