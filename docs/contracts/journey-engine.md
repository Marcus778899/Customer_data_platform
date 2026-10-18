# 契約：旅程引擎 v0.1.0 — 認證、旅程建立、事件攝取、軌跡查詢（v1）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-01.md；欄位語意正本見 docs/spec/ -->

## §1 通用

- Base URL：前端一律用相對路徑 `/api`（dev server 轉給後端，見 `web/vite.config.ts`）；後端 port 為環境變數 `API_PORT`
- Auth：§1.1（D-013）；`tenant_id` 一律取環境變數 `CDP_TENANT_ID`，請求 body 帶 `tenant_id` 會被忽略
- 跨域：不開 CORS（同源經 proxy）
- JSON 欄位 `snake_case`；時間為 ISO-8601 UTC（`2026-10-18T02:00:00Z`）；期間為 ISO-8601 Duration（`PT1H`）；ID 為 ULID 字串
- 所有時間都取自後端注入的 `Clock`（含 `received_at`、`as_of`）：正式環境 = 系統時間；測試快轉時 = 模擬時間，同一筆回應不會混用兩種時間
- 每個回應都有標頭 `X-Request-Id`；請求帶合法的 `X-Request-Id`（1–64 字元 `[A-Za-z0-9._-]`）會沿用，否則後端產生
- 型別欄的 `T | null` 表示可為 `null`；沒寫 `null` 的欄位一定有值

### §1.1 認證與角色（D-013）

| 角色 | 憑證 | 可用 API |
|---|---|---|
| `INGEST`（外部系統） | 標頭 `Authorization: Bearer <金鑰>`，金鑰 = 環境變數 `CDP_INGEST_API_KEY` | §3.2 |
| `ADMIN`（建旅程、腳本、測試） | 標頭 `Authorization: Bearer <金鑰>`，金鑰 = 環境變數 `CDP_ADMIN_API_KEY` | §3.1、§4 |
| `SUPPORT`（客服，軌跡頁） | §1.2 登入取得的 cookie `CDP_SESSION` | §4、§1.2 |

- 不需認證：`POST /api/v1/auth/login`、`POST /api/v1/auth/logout`；其他 `/api/**` 都需要
- 帶了 `Authorization` 就只看它、不看 cookie；session 只能用於 `GET`，寫入 API（§3）只收 Bearer
- `401` `UNAUTHENTICATED`：沒帶憑證、金鑰不符、session 不存在或逾時；回應標頭 `WWW-Authenticate: Bearer`
- `403` `FORBIDDEN`：憑證有效但角色不在上表（例：`SUPPORT` 呼叫 §3、`INGEST` 呼叫 §4）
- 先驗認證再驗輸入：未認證一律 `401`，不會先回 `400`／`404`

### §1.2 客服登入

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | `Content-Type: application/json`；`{ "username", "password" }` 皆必填 | `204`，`Set-Cookie: CDP_SESSION=…; Path=/api; HttpOnly; Secure; SameSite=Strict`；每次登入換新 session id | `400` `VALIDATION_FAILED`、`401` `INVALID_CREDENTIALS`（不區分帳號或密碼錯）、`415` `UNSUPPORTED_MEDIA_TYPE` |
| POST | `/api/v1/auth/logout` | — | `204`，清除 cookie；沒有 session 也回 `204` | — |
| GET | `/api/v1/auth/me` | — | `200` `{ "username": string }` | `401` `UNAUTHENTICATED`；`403` `FORBIDDEN`（Bearer 金鑰呼叫；只開給 `SUPPORT`） |

- session 閒置逾時 = 環境變數 `CDP_SESSION_IDLE_TIMEOUT`；後端重啟後 session 全部失效
- 前端：任何 API 回 `401` `UNAUTHENTICATED` → 導向登入，登入後回原頁；cookie 為 HttpOnly，前端讀不到，用 `/me` 判斷是否已登入

## §2 共用列舉

| 型別 | 值 |
|---|---|
| `EnrollmentStatus` | `PENDING` `ACTIVE` `WAITING` `SUSPENDED` `COMPLETED` `GOAL_MET` `EXITED` `FAILED` `ABORTED`（語意見 spec 02 §3；v0.1.0 實際會出現 `ACTIVE` `WAITING` `COMPLETED` `FAILED`） |
| `DeliveryStatus` | `PENDING`（已排入、尚未送） `SENT` `SKIPPED` `FAILED` `CANCELLED` |
| `SkipReason` | `CONSENT` `FREQUENCY_CAP` `QUIET_HOURS` `PRIORITY_LOST` `BUDGET_EXCEEDED` `KILL_SWITCH`（v0.1.0 無守門，不會出現） |
| `MessageType` | `TRANSACTIONAL` `MARKETING`；v0.1.0 無模板表，一律 `null` |
| 節點 `type` | v0.1.0 只接受 `StartNode` `EventSourceNode` `WaitDelayNode` `SendMessageNode` `EndNode` |

## §3 寫入 API（只收 Bearer，角色見 §1.1；另可回 `401`／`403`）

### §3.1 建立旅程版本 `POST /api/v1/journeys`

- Request：spec 03 §2 頂層結構去掉 `tenant_id`、`version`、`status`：`journey_id`（省略 = 新旅程，後端產生）、`name`（必填）、`description`、`settings`（物件，v0.1.0 不評估）、`entry_node`、`nodes[]`（`id` `type` `name` `config` `outputs`，見 spec 03 §4）
- 規則：`entry_node` 指向唯一的 `StartNode`；`StartNode.outputs.next` 指向 `EventSourceNode`（可為陣列）；每個 `outputs` 目標都存在；無迴圈；每條路徑終於 `EndNode`；`EventSourceNode.config.event_name` 必填、`event_filter`／`profile_filter` 須為 `null` 或省略；`WaitDelayNode.config.duration` 為正的 Duration；`SendMessageNode.config` 需 `template_id`、`channel_id`，`outputs` 需 `sent`（`skipped` 可省略）；`node.name` 可省略
- 成功 `201`：`{ "journey_id", "version", "status": "ACTIVE", "published_at" }`；同 `journey_id` 再送 = 新版本，進行中的 Enrollment 不受影響
- 錯誤：`400` `VALIDATION_FAILED`（含 `journey_id` 不是 ULID）；`404` `JOURNEY_NOT_FOUND`（帶了 `journey_id` 但該旅程不存在；要建新旅程請省略 `journey_id`）

### §3.2 事件攝取 `POST /api/v1/events`、`POST /api/v1/events/batch`

- Request（單筆）：spec 01 §3 欄位；v0.1.0 子集規則：`event_id` ULID 必填；`source_type` 必填；`source_id` 依 spec 01 §4；`name` 符合 `^[a-z][a-z0-9_]*$`；`occurred_at` 必填且不得超前伺服器時間 5 分鐘；`identity.profile_id` **必填**（v0.1.0 無身分解析，`identifiers` 可帶、只存不用）；`INTERNAL_OPERATION` 需 `context.actor_id`；`CUSTOMER_BEHAVIOR` 不得帶 `context.actor_id`／`actor_role`
- Request（批次）：`{ "events": [ … ] }`，1–500 筆
- 單筆回應：`202` `{ "event_id", "status": "ACCEPTED" | "DUPLICATE", "errors": [] }`；驗證失敗 `400`（§5，`code` = `VALIDATION_FAILED`）
- 批次回應：`202` `{ "accepted", "rejected", "results": [ { "event_id", "status", "errors": [ { "field", "message" } ] } ] }`，`accepted` 含 `DUPLICATE`，部分成功不整批失敗；超過 500 筆 `400` `BATCH_TOO_LARGE`
- 攝取只落地；Enrollment 由後端非同步建立（輪詢間隔 `ENGINE_POLL_INTERVAL`）。`occurred_at` 早於接收 24 小時以上：`ACCEPTED` 但不建立 Enrollment（spec 01 §6）

## §4 查詢 API（唯讀，D-005；前端依此 mock；角色見 §1.1，所有端點另可回 `401`／`403`）

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
| | `journey_name` | string | §4.2；不為 `null`（§3.1 `name` 必填） |
| | `current_node_name` | string \| null | §4.2 |
| | `status` | `EnrollmentStatus` | |
| | `enrolled_at` `updated_at` | time | |
| | `terminated_at` | time \| null | 未結束為 `null` |
| `EnrollmentDetail` | （`EnrollmentSummary` 全部欄位） | | |
| | `termination_reason` | string \| null | 人可讀，規則同 §4.3 |
| | `wake_condition` | `WakeCondition` \| null | 僅 `WAITING` 有值 |
| | `variables` | object | 旅程變數，v0.1.0 為 `{}`；已遮罩（§4.4） |
| | `trigger_snapshot` | object | `event_id` `event_name` `source_type`（string）`occurred_at`（time）`properties`（object，事件屬性，已遮罩）`profile_snapshot`（object，已遮罩；v0.1.0 一律 `{}`） |
| | `trace` | `TraceEntry[]` | 全部軌跡，依 `seq` 由舊到新 |
| | `delivery_history` | `Delivery[]` | 最新 50 筆，新到舊（Q6） |
| | `delivery_history_next_cursor` | string \| null | 更舊紀錄用 `/deliveries?cursor=` 續查 |
| | `as_of` | time | 產生此回應時的後端時間（Q4） |
| | `masked_paths` | string[] | 被遮罩的值的路徑（§4.4），沒有則 `[]` |
| `WakeCondition` | `type` | `TIMER` \| `EVENT` \| `SIGNAL` | v0.1.0 只有 `TIMER` |
| | `wake_at` | time \| null | `TIMER`：預計喚醒時間；實際喚醒最多晚一個輪詢間隔 |
| | `event_name` `signal_name` | string \| null | v0.2.0 起使用，v0.1.0 為 `null` |
| | `timeout_at` | time \| null | 同上 |
| `TraceEntry` | `seq` | int | Enrollment 內遞增，從 1 開始 |
| | `node_id` `node_type` | string | `node_type` 為完整型別名，例 `WaitDelayNode` |
| | `node_name` | string \| null | §4.2 |
| | `entered_at` | time | |
| | `exited_at` | time \| null | 仍在此節點為 `null`（= 目前節點） |
| | `out_port` | string \| null | 出口名，v0.1.0：`next`、`sent`；仍在節點或終點為 `null` |
| | `decision_reason` | string | 必有，§4.3 |
| | `evaluated` | object | 判斷時實際取到的值，鍵 `snake_case`，可能為 `{}`；已遮罩（§4.4） |
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

可直接給客服看：繁體中文、純文字（無 HTML／Markdown）、一句完整句、≤ 200 字；寫出實際判斷依據的值與時間（ISO-8601 UTC），不寫內部堆疊或 SQL。只能引用節點設定與 §4.4 引擎鍵的值（事件名、期間、時間、ID），**不得**引用事件屬性、客戶屬性或旅程變數的值（文字無法事後遮罩）。

### §4.4 個資遮罩（D-014）

後端在回應前遮罩，所有角色相同（v0.1.0 不提供解除遮罩）。只有 `EnrollmentDetail` 含個資；`EnrollmentSummary`、`Delivery` 只有 ID、名稱、狀態與時間，不遮罩。

| 範圍 | 明文的鍵 |
|---|---|
| `variables`、`trigger_snapshot.properties`、`trigger_snapshot.profile_snapshot` | 環境變數 `CDP_TRACE_PLAIN_KEYS` 列出的鍵；未列 = 遮罩（D-021） |
| `trace[].evaluated` | 上列鍵＋引擎鍵 `event_id` `event_name` `duration` `wake_at` `template_id` `channel_id` `idempotency_key` |
| 其他欄位（`trigger_snapshot` 的 `event_id` `event_name` `occurred_at` `source_type`、`profile_id`、各 ID 與時間） | 全部明文 |

- `CDP_TRACE_PLAIN_KEYS` 解析：以逗號切開、去前後空白，捨棄空字串與 `-`；值為 `-`（或空）= 沒有明文鍵、全部遮罩
- 逐層比對：物件的鍵在明文清單 → 整棵子樹明文；否則往下走；陣列元素沿用所屬鍵的判定。只遮「值」，鍵名一律保留
- 遮罩後的值一律是字串：符合 `^[^@\s]+@[^@\s]+$` 的字串 → 首字＋`***@`＋網域（`a***@example.com`）；其他字串、數字、布林 → `"***"`；`null` 保持 `null`
- `masked_paths`：每個被遮罩的值一條，點號＋`[索引]`（0 起算）路徑，例 `trigger_snapshot.properties.email`、`trace[1].evaluated.phone`、`variables.items[0].name`；前端以此判斷該值是遮罩、不靠比對 `***`

## §5 錯誤格式

`Content-Type: application/problem+json`（RFC 9457，不受請求 `Accept` 影響）；HTTP status 區分大類，`code` 區分細項。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `type` | string | 固定 `about:blank` |
| `title` | string | HTTP 狀態短語 |
| `status` | int | 同 HTTP status |
| `code` | string | 下表其一 |
| `detail` | string | 人可讀說明；5xx 不含內部訊息 |
| `request_id` | string | 同回應標頭 `X-Request-Id`，供回報 |
| `errors` | `{ field, message }[]` | 僅 `400`；`field` 為 JSON 路徑（`identity.profile_id`、`events[3].occurred_at`、`cursor`） |

| HTTP | `code` | 何時 | 前端處理 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | 欄位驗證失敗、JSON 無法解析、參數缺漏或型別錯 | 依 `errors` 標示欄位 |
| `400` | `BATCH_TOO_LARGE` | §3.2 批次超過 500 筆 | — |
| `401` | `UNAUTHENTICATED`／`INVALID_CREDENTIALS` | §1.1／§1.2 | 導向登入／顯示帳密錯誤 |
| `403` | `FORBIDDEN` | §1.1 | 此角色不可用 |
| `404` | `ENROLLMENT_NOT_FOUND`／`JOURNEY_NOT_FOUND` | §4／§3.1 | 這筆資料不存在，不必重試 |
| `404` | `NOT_FOUND` | 無此路由 | — |
| `405` | `METHOD_NOT_ALLOWED` | 路由存在、方法不符；回應帶 `Allow` 標頭 | — |
| `406` | `NOT_ACCEPTABLE` | `Accept` 不接受 `application/json` | — |
| `413` | `PAYLOAD_TOO_LARGE` | 保留：請求大小上限未定案前不會出現 | — |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 不是 `application/json`；回應帶 `Accept` 標頭 | — |
| 其他 `4xx` | `VALIDATION_FAILED` | 未列的框架 4xx，沿用原 status（`400` 以外不帶 `errors`） | — |
| `500` | `INTERNAL_ERROR` | 未預期錯誤 | 可重試 |
| `503` | `SERVICE_UNAVAILABLE` | 資料庫連不上 | 可重試 |

- 過渡：`405`／`406` 的專用 code 與 `405` `Allow`、`415` `Accept` 標頭在 FIX-02 實作；之前 `405`／`406` 回 `VALIDATION_FAILED`、兩種標頭皆無（status 已正確）。前端與 §6 範例不依賴這兩個 code

## §6 範例

`POST /api/v1/auth/login` body `{ "username": "…", "password": "…" }` → `204`（帳密錯 → `401` `INVALID_CREDENTIALS`）；以下 §4 範例皆帶 `CDP_SESSION` cookie

`GET /api/v1/enrollments?q=01JCXYZPROFILE00000000001&status=WAITING,COMPLETED` → `200`

```json
{ "items": [ { "enrollment_id": "01JCXYZENROLL000000000001", "journey_id": "01JCXYZJOURNEY0000000001", "journey_version": 2,
  "journey_name": "棄單挽回", "profile_id": "01JCXYZPROFILE00000000001", "status": "WAITING",
  "current_node_id": "wait_1", "current_node_name": "等 3 天", "current_node_type": "WaitDelayNode",
  "enrolled_at": "2026-10-18T02:00:00Z", "updated_at": "2026-10-18T02:00:00Z", "terminated_at": null } ],
  "next_cursor": null }
```

`GET /api/v1/enrollments/01JCXYZENROLL000000000001` → `200`（`CDP_TRACE_PLAIN_KEYS=cart_id,cart_total`）

```json
{ "enrollment_id": "01JCXYZENROLL000000000001", "journey_id": "01JCXYZJOURNEY0000000001", "journey_version": 2,
  "journey_name": "棄單挽回", "profile_id": "01JCXYZPROFILE00000000001", "status": "WAITING",
  "current_node_id": "wait_1", "current_node_name": "等 3 天", "current_node_type": "WaitDelayNode",
  "enrolled_at": "2026-10-18T02:00:00Z", "updated_at": "2026-10-18T02:00:00Z", "terminated_at": null,
  "termination_reason": null,
  "wake_condition": { "type": "TIMER", "wake_at": "2026-10-21T02:00:00Z", "event_name": null, "timeout_at": null, "signal_name": null },
  "variables": {},
  "trigger_snapshot": { "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB", "event_name": "cart_item_added", "occurred_at": "2026-10-18T01:59:41Z",
    "source_type": "CUSTOMER_BEHAVIOR", "properties": { "cart_id": "CART-99201", "cart_total": 1580, "email": "a***@example.com", "phone": "***" },
    "profile_snapshot": {} },
  "trace": [
    { "seq": 1, "node_id": "src_1", "node_type": "EventSourceNode", "node_name": "加入購物車", "entered_at": "2026-10-18T02:00:00Z",
      "exited_at": "2026-10-18T02:00:00Z", "out_port": "next", "decision_reason": "收到事件 cart_item_added，建立 Enrollment。",
      "evaluated": { "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB" } },
    { "seq": 2, "node_id": "wait_1", "node_type": "WaitDelayNode", "node_name": "等 3 天", "entered_at": "2026-10-18T02:00:00Z",
      "exited_at": null, "out_port": null, "decision_reason": "等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。",
      "evaluated": { "duration": "P3D", "wake_at": "2026-10-21T02:00:00Z" } } ],
  "delivery_history": [], "delivery_history_next_cursor": null, "as_of": "2026-10-18T02:00:05Z",
  "masked_paths": [ "trigger_snapshot.properties.email", "trigger_snapshot.properties.phone" ] }
```

`GET /api/v1/enrollments/01JNOTEXIST000000000000000` → `404`

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "code": "ENROLLMENT_NOT_FOUND",
  "detail": "找不到這筆 Enrollment。", "request_id": "01JCXYZREQ0000000000000001" }
```

`POST /api/v1/events`（`Authorization: Bearer <CDP_INGEST_API_KEY 的值>`）body = spec 01 §8 第一個範例加上 `"identity": { "profile_id": "01JCXYZPROFILE00000000001" }` → `202` `{ "event_id": "01JCXYZ8K3M4N5P6Q7R8S9T0AB", "status": "ACCEPTED", "errors": [] }`

`GET /api/v1/enrollments?q=x`（未帶憑證）→ `401`

```json
{ "type": "about:blank", "title": "Unauthorized", "status": 401, "code": "UNAUTHENTICATED",
  "detail": "請先登入。", "request_id": "01JCXYZREQ0000000000000002" }
```
