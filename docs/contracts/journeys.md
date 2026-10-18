# 契約：旅程管理、版本與畫布（v1）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-03.md；定義格式正本見 spec 03。§2 屬第一版（v0.2.0），§3～§5 屬第二版（拆版見 DESIGN-03） -->

- 通用規則：`journey-engine.md` §1、§5；憑證、租戶、角色：`tenancy-auth.md` §1、§2（本檔 API 皆為「租戶」API）
- 旅程（journey）有一個生命週期狀態；版本（version）發佈後不可變（spec 03 §1）。Enrollment 綁定建立當下的最新版本，之後發佈新版不影響（spec 02 §2）

## §1 資料模型

| 型別 | 欄位 | 型別 | 規則 |
|---|---|---|---|
| `JourneyStatus` | — | enum | `DRAFT`（從未發佈）`ACTIVE`（接受新進入）`ABORTED`（已停止，終態，§3.5）；其餘 spec 03 §2 狀態不在本版出現 |
| `JourneySummary` | `journey_id` `name` | string | |
| | `status` | `JourneyStatus` | |
| | `latest_version` | int \| null | `DRAFT` 為 `null` |
| | `has_draft` | bool | 有未發佈的草稿（§3） |
| | `updated_at` | time | |
| | `published_at` | time \| null | 最新版本的發佈時間 |
| `JourneyDetail` | （`JourneySummary` 全部欄位）＋`description` | string \| null | |
| | `versions` | `{ version, published_at }[]` | 新到舊 |
| | `stopped_at` `stop_reason` | time \| null、string \| null | 僅 `ABORTED` 有值 |
| `JourneyVersion` | `journey_id` `name` | string | |
| | `version` | int | |
| | `description` | string \| null | |
| | `settings` | object | spec 03 §3 |
| | `entry_node` | string | |
| | `nodes` | `Node[]` | spec 03 §4：`id` `type` `name`（string \| null）`config` `outputs` |
| | `layout` | `Layout` \| null | 畫布座標；v0.1.0 建立的版本為 `null`（前端自動排版） |
| | `published_at` | time | |
| `Layout` | `nodes` | `{ [node_id]: { "x": number, "y": number } }` | 只放畫面座標，引擎不讀；未列的節點由前端自動排版 |

## §2 唯讀（第一版）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| GET | `/api/v1/journeys` | `status`（選填，逗號分隔）、`q`（選填，名稱包含，不分大小寫）、`cursor`、`limit`（1–100，預設 20） | `200` `Page<JourneySummary>`，依 `updated_at` 新到舊 | `400` |
| GET | `/api/v1/journeys/{journey_id}` | — | `200` `JourneyDetail` | `404` `JOURNEY_NOT_FOUND` |
| GET | `/api/v1/journeys/{journey_id}/versions/{version}` | — | `200` `JourneyVersion` | `404` `JOURNEY_NOT_FOUND`（旅程或版本不存在） |

- 圖形軌跡：前端以 `EnrollmentDetail` 的 `journey_id`＋`journey_version` 取 `JourneyVersion` 畫圖，疊上 `trace`（推導規則見 `journey-engine.md` §4.5）
- 第一版 `has_draft` 一律 `false`、`status` 只會是 `ACTIVE`（旅程仍以 journey-engine §3.1 建立）

## §3 草稿與發佈（第二版）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/journeys/drafts` | `{ "name" }` | `201` `{ "journey_id", "status": "DRAFT", "revision": 1 }`；草稿內容為只有 `StartNode` 的骨架 | `400` |
| GET | `/api/v1/journeys/{journey_id}/draft` | — | `200` `Draft` | `404` `JOURNEY_NOT_FOUND`／`DRAFT_NOT_FOUND` |
| PUT | `/api/v1/journeys/{journey_id}/draft` | `{ "revision", "definition", "layout" }` | `200` `Draft`（`revision` +1）；沒有草稿時以最新版本為底建立（`revision` 帶 `0`） | `400`（只驗結構：`definition` 是物件、`nodes` 是陣列、≤ 200 個節點、整包 ≤ 256 KB）、`404`、`409` `DRAFT_CONFLICT`（`revision` 不是目前值）、`409` `JOURNEY_ABORTED` |
| DELETE | `/api/v1/journeys/{journey_id}/draft` | — | `204`（沒有也 `204`）；`DRAFT` 狀態的旅程不可刪草稿 → `409` `DRAFT_REQUIRED` | `404` |
| POST | `/api/v1/journeys/{journey_id}/draft/validate` | — | `200` `ValidationReport`（不改資料） | `404` |
| POST | `/api/v1/journeys/{journey_id}/publish` | `{ "revision" }` | `201` `{ "journey_id", "version", "status": "ACTIVE", "published_at" }`；草稿清除 | `400` `VALIDATION_FAILED`（`errors` 同 `ValidationReport.errors`）、`404`、`409` `DRAFT_CONFLICT`／`JOURNEY_ABORTED` |

- `Draft`：`journey_id`、`revision` int、`definition`（`name` `description` `settings` `entry_node` `nodes`，同 `JourneyVersion` 去掉 `version` `published_at`）、`layout`、`updated_at`、`updated_by`（`user_id` \| null；金鑰呼叫為 `null`）
- `ValidationReport`：`valid` bool、`errors` `{ field, message }[]`、`warnings` `{ field, message }[]`；`field` 為 JSON 路徑（`definition.nodes[3].outputs.yes`）；規則 = spec 03 §6 中本版支援者（§4）＋ journey-engine §3.1 規則（「無迴圈」改為 spec 03 §6 規則 7）
- 名稱與描述只能經草稿發佈修改（`name` 屬於版本）

### §3.5 停止 `POST /api/v1/journeys/{journey_id}/stop`

- Request：`{ "reason" }`（1–200 字）；成功 `200` `JourneyDetail`（`status` = `ABORTED`）；已停止再停 → `200`（不改原因）；`DRAFT` → `409` `JOURNEY_NOT_PUBLISHED`
- 效果（spec 05 §5 旅程層）：立即不再接受新進入；未推送的發送與已推送未送出的發送都取消（`DeliveryStatus` = `CANCELLED`）；進行中的 Enrollment 由背景轉 `ABORTED`（`termination_reason` 含停止原因），完成時間取決於數量，不同步等待；不可恢復

## §4 第二版支援的節點與條件

| `type` | `config` 必填 | `outputs` 必填 |
|---|---|---|
| v0.1.0 五種 | journey-engine §3.1 | 同左 |
| `WaitForEventNode` | `event_name`；`timeout`（正 Duration 或 `null` = 不逾時）；`event_filter`（同 `EventSourceNode` 規則：`null` 或省略） | `occurred`；`timeout` 不為 `null` 時 `timeout` 必填 |
| `ConditionNode` | `condition`（下表子集） | `yes` `no` |
| `SplitTestNode` | `variants`（2–10 個；`port` snake_case、`weight` 正整數、總和 100、`label`；至多一個 `is_control`）；`assignment_seed` 省略或 `STABLE` | 每個 `variants[].port` |
| `MergeNode` | — | `next` |
| `ExitNode` | `reason`（1–200 字） | — |
| `GoalNode` | `goal_id` | — |

- 條件樹子集（spec 06 §2～§4）：群組 `op` = `AND` `OR` `NOT`（`NOT` 恰一個子節點），深度 ≤ 5；葉節點只支援 `type: "attribute"`，`field` = 客戶屬性鍵或 `trigger.properties.<鍵>`；`operator` = `EQ` `NEQ` `IN` `NOT_IN` `GT` `GTE` `LT` `LTE` `CONTAINS` `IS_NULL` `IS_NOT_NULL`；`behavior`、`segment` 葉節點 → 驗證錯誤
- `settings` 支援：`reentry`（spec 03 §3，五種 `mode`；省略 = `UNLIMITED`）；`goal`（`condition` 只支援 `{ "type": "event", "event" }`）；`global_exit`（條件為 `event`〔`since` 只支援 `ENROLLMENT`〕或上列 `attribute` 葉節點）；其他鍵存而不用
- 迴圈：允許，但每個強連通分量須含 `WaitDelayNode` 或 `WaitForEventNode`（spec 03 §6 規則 7）
- `EnrollmentStatus` 本版會出現 `GOAL_MET` `EXITED` `ABORTED`；`TraceEntry.out_port` 會出現各節點的出口名

## §5 節點統計（第二版） `GET /api/v1/journeys/{journey_id}/versions/{version}/node-stats`

`200` `{ "items": [ { "node_id", "entered": int, "exited": int, "current": int } ], "as_of" }`：該版本所有 Enrollment 的軌跡計數（`entered` = 軌跡列數，`exited` = 已離開的列數，`current` = 停在該節點的 Enrollment 數）；沒有軌跡的節點不列。錯誤 `404` `JOURNEY_NOT_FOUND`

## §6 範例

`GET /api/v1/journeys/01JCXYZJOURNEY0000000001/versions/2` → `200`

```json
{ "journey_id": "01JCXYZJOURNEY0000000001", "version": 2, "name": "棄單挽回", "description": null,
  "settings": {}, "entry_node": "start_1", "published_at": "2026-10-18T01:00:00Z", "layout": null,
  "nodes": [ { "id": "start_1", "type": "StartNode", "name": null, "config": {}, "outputs": { "next": "src_1" } },
    { "id": "src_1", "type": "EventSourceNode", "name": "加入購物車", "config": { "event_name": "cart_item_added" }, "outputs": { "next": "wait_1" } },
    { "id": "wait_1", "type": "WaitDelayNode", "name": "等 3 天", "config": { "duration": "P3D" }, "outputs": { "next": "end_1" } },
    { "id": "end_1", "type": "EndNode", "name": null, "config": {}, "outputs": {} } ] }
```

`PUT /api/v1/journeys/01JCXYZJOURNEY0000000001/draft` `{ "revision": 3, … }`（目前為 4）→ `409` `DRAFT_CONFLICT`
