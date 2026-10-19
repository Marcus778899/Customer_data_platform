# 契約：旅程管理、版本與畫布（v1）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-03.md；定義格式正本見 spec 03。§2 屬第一版（v0.2.0），§3～§5 屬第二版（v0.3.0，D-055） -->

- 通用規則：`journey-engine.md` §1、§5；憑證與角色：`auth.md` §1、§2
- 旅程（journey）有一個生命週期狀態；版本（version）發佈後不可變（spec 03 §1）。Enrollment 綁定建立當下的最新版本，之後發佈新版不影響（spec 02 §2）
- 第二版起發佈一律經管理員審核（D-056）：送審 → 管理員核准才建立版本並上線（§3.2）；可調整的審核規則集中在 §3.3（D-060）

## §1 資料模型

| 型別 | 欄位 | 型別 | 規則 |
|---|---|---|---|
| `JourneyStatus` | — | enum | `DRAFT`（從未發佈）`ACTIVE`（接受新進入）`ABORTED`（已停止，終態，§3.5）；其餘 spec 03 §2 狀態不在本版出現 |
| `JourneySummary` | `journey_id` `name` | string | |
| | `status` | `JourneyStatus` | |
| | `latest_version` | int \| null | `DRAFT` 為 `null` |
| | `has_draft` | bool | 有未發佈的草稿（§3） |
| | `review_status` | `PENDING` \| `REJECTED` \| null | 目前草稿的審核狀態（§3.2）：送審中 `PENDING`；最新一筆審核被退回且草稿仍在 `REJECTED`；其他 `null` |
| | `updated_at` | time | |
| | `published_at` | time \| null | 最新版本的發佈時間 |
| `JourneyDetail` | （`JourneySummary` 全部欄位）＋`description` | string \| null | |
| | `versions` | `{ version, published_at }[]` | 新到舊 |
| | `stopped_at` `stop_reason` | time \| null、string \| null | 僅 `ABORTED` 有值 |
| | `stopped_by` | `UserRef` \| null | 僅 `ABORTED` 有值；金鑰呼叫為 `null` |
| `JourneyVersion` | `journey_id` `name` | string | |
| | `version` | int | |
| | `description` | string \| null | |
| | `settings` | object | spec 03 §3 |
| | `entry_node` | string | |
| | `nodes` | `Node[]` | spec 03 §4：`id` `type` `name`（string \| null）`config` `outputs` |
| | `layout` | `Layout` \| null | 畫布座標；v0.1.0 建立的版本為 `null`（前端自動排版） |
| | `published_at` | time | |
| `Layout` | `nodes` | `{ [node_id]: { "x": number, "y": number } }` | 只放畫面座標，引擎不讀；未列的節點由前端自動排版 |
| `UserRef` | `user_id` `display_name` | string | 後端帶出使用者目前的顯示名稱（停用者照常帶出），不需呼叫 `/users` |
| `Review`（第二版，§3.2） | `review_id` | string（ULID） | |
| | `journey_id` | string | |
| | `action` | `ReviewAction` | `PUBLISH`（其他值見 §3.3） |
| | `status` | `ReviewStatus` | `PENDING`（送審中）`APPROVED` `REJECTED` `WITHDRAWN`（撤回，或旅程停止而取消）；後三者為終態 |
| | `revision` | int \| null | 送審當下的草稿 `revision`（核准的就是這份內容）；§3.3 第 3 列的金鑰紀錄為 `null` |
| | `base_version` | int \| null | 送審當下的 `latest_version` |
| | `note` | string \| null | 送審說明，0–500 字 |
| | `submitted_by` `submitted_at` | `UserRef` \| null、time | `null` 只出現在 §3.3 第 3 列的金鑰紀錄 |
| | `decided_by` `decided_at` | `UserRef` \| null、time \| null | `PENDING` 為 `null`；旅程停止造成的 `WITHDRAWN` 為停止者 |
| | `reason` | string \| null | 退回原因（`REJECTED` 必有）、核准備註（選填）、撤回原因（選填；停止造成者為「旅程已停止」） |
| | `version` | int \| null | `APPROVED` 建立的版本號 |

## §2 唯讀（第一版）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| GET | `/api/v1/journeys` | `status`（選填，逗號分隔）、`q`（選填，名稱包含，不分大小寫）、`cursor`、`limit`（1–100，預設 20） | `200` `Page<JourneySummary>`，依 `updated_at` 新到舊 | `400` |
| GET | `/api/v1/journeys/{journey_id}` | — | `200` `JourneyDetail` | `404` `JOURNEY_NOT_FOUND` |
| GET | `/api/v1/journeys/{journey_id}/versions/{version}` | — | `200` `JourneyVersion` | `404` `JOURNEY_NOT_FOUND`（旅程或版本不存在） |

- 圖形軌跡：前端以 `EnrollmentDetail` 的 `journey_id`＋`journey_version` 取 `JourneyVersion` 畫圖，疊上 `trace`（推導規則見 `journey-engine.md` §4.5）
- 第一版沒有 `journey` 表（V7 屬第二版），`JourneySummary`／`JourneyDetail` 由 `journey_definition`（spec 07 §3）推導；第二版改讀 `journey` 表（spec 07 §12），欄位語意不變：

| 欄位 | 第一版推導（每個 `journey_id` 取 `version` 最大的一列 = 最新列） | 第二版 |
|---|---|---|
| `name` `description` | 最新列 | 最新版本（`latest_version`）；`DRAFT` 取草稿 |
| `status` | 最新列的 `status`（v0.1.0 建立的一律 `ACTIVE`） | `journey.status` |
| `latest_version` | `max(version)` | `journey.latest_version` |
| `published_at` | 最新列的 `published_at` | 最新版本的 `published_at` |
| `updated_at` | 最新列的 `created_at` | `journey.updated_at`（建立、存草稿、發佈、停止、§3.1 同步時更新） |
| `has_draft` | 一律 `false` | `journey.draft` 不為 `null` |
| `review_status` | 一律 `null`（第一版沒有審核，列表不顯示「待審核」） | 依 §1 定義，由 `journey_review` 該旅程最新一筆與 `journey.draft` 推導 |
| `versions` | 該 `journey_id` 全部列 | 同左 |

- `q`、`status` 篩選與排序都套在推導後的值上；第二版另加篩選 `review_status`（選填，`PENDING`／`REJECTED`），管理員的「待審核」清單 = `?review_status=PENDING`

## §3 草稿與送審（第二版）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/journeys/drafts` | `{ "name" }` | `201` `{ "journey_id", "status": "DRAFT", "revision": 1 }`；草稿內容為只有 `StartNode` 的骨架 | `400` |
| GET | `/api/v1/journeys/{journey_id}/draft` | — | `200` `Draft` | `404` `JOURNEY_NOT_FOUND`／`DRAFT_NOT_FOUND` |
| PUT | `/api/v1/journeys/{journey_id}/draft` | `{ "revision", "definition", "layout" }` | `200` `Draft`（`revision` +1）；沒有草稿時以最新版本為底建立（`revision` 帶 `0`） | `400`（只驗結構：`definition` 是物件、`nodes` 是陣列、≤ 200 個節點、整包 ≤ 256 KB）、`404`、`409` `DRAFT_CONFLICT`（`revision` 不是目前值）、`409` `DRAFT_IN_REVIEW`（送審中，§3.2）、`409` `JOURNEY_ABORTED` |
| DELETE | `/api/v1/journeys/{journey_id}/draft` | — | `204`（沒有也 `204`）；`DRAFT` 狀態的旅程不可刪草稿 → `409` `DRAFT_REQUIRED`；送審中 → `409` `DRAFT_IN_REVIEW` | `404` |
| POST | `/api/v1/journeys/{journey_id}/draft/validate` | — | `200` `ValidationReport`（不改資料） | `404` |

- 沒有直接發佈的 API：經 §3.2 送審、核准
- `Draft`：`journey_id`、`revision` int、`definition`（`name` `description` `settings` `entry_node` `nodes`，同 `JourneyVersion` 去掉 `version` `published_at`）、`layout`、`updated_at`、`updated_by`（`UserRef` \| null；金鑰呼叫為 `null`）、`latest_review`（`Review` \| null：這份草稿最新一筆審核紀錄；`status` = `PENDING` 時草稿鎖定）
- `ValidationReport`：`valid` bool、`errors` `Issue[]`、`warnings` `Issue[]`；規則 = spec 03 §6 中本版支援者（§4）＋ journey-engine §3.1 規則（「無迴圈」改為 spec 03 §6 規則 7）
- `Issue`：`field` string（JSON 路徑，例 `definition.nodes[3].outputs.yes`）、`message` string（繁體中文一句，可直接顯示）、`rule` int \| null（spec 03 §6 規則編號；該表沒有編號的檢查，例 §4 的 `config` 必填，為 `null`）、`node_id` string \| null（錯誤所在節點；`settings`、`name` 等不屬於單一節點者為 `null`；規則 7 每個違規的迴圈一筆，取迴圈中在 `nodes` 陣列最前的節點，`message` 列出迴圈全部節點 id）
- 送審、核准的 `400` `VALIDATION_FAILED`：`errors` 每筆同 `Issue`（journey-engine §5 的 `{ field, message }` 再加 `rule`、`node_id`）
- 名稱與描述只能經草稿送審、核准後修改（`name` 屬於版本）

### §3.1 旅程列的同步（第二版）

journey-engine §3.1 `POST /api/v1/journeys` 第二版仍可用（金鑰、demo-seed），在建立版本的同一交易內更新 `journey` 列：

| 該旅程的狀態 | 效果 |
|---|---|
| 新旅程（省略 `journey_id`） | 插入 `journey`：`status` = `ACTIVE`、`latest_version` = 1 |
| `DRAFT` | 建立版本 1，`status` → `ACTIVE`、`latest_version` = 1；草稿保留 |
| `ACTIVE` | `latest_version` = 新版本號 |
| `ABORTED` | `409` `JOURNEY_ABORTED`，不建版本 |

- 以上都把 `updated_at` 設為現在；草稿與 `revision` 不變（之後核准的草稿會成為再下一版）；送審中的 `Review` 不受影響
- 只收管理金鑰（`auth.md` §2）；是否經審核與留紀錄方式見 §3.3 第 3 列

### §3.2 審核（D-056；角色見 `auth.md` §2）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/journeys/{journey_id}/draft/submit` | `{ "revision", "note"? }` | `201` `Review`（`PENDING`）；草稿鎖定 | `400` `VALIDATION_FAILED`（先跑 `validate`，有錯不建立）、`404` `DRAFT_NOT_FOUND`、`409` `DRAFT_CONFLICT`／`REVIEW_PENDING`（已有送審中）／`JOURNEY_ABORTED` |
| POST | `/api/v1/journeys/{journey_id}/reviews/{review_id}/approve` | `{ "reason"? }` | `200` `Review`（`APPROVED`，含 `version`）；同一交易：以送審的草稿建立新版本、`status` → `ACTIVE`、`latest_version` = 新版本、清除草稿 | `400` `VALIDATION_FAILED`（重驗失敗，維持 `PENDING`）、`403` `SELF_APPROVAL_FORBIDDEN`（§3.3）、`404` `REVIEW_NOT_FOUND`、`409` `REVIEW_NOT_PENDING` |
| POST | `/api/v1/journeys/{journey_id}/reviews/{review_id}/reject` | `{ "reason" }`（1–500 字，必填） | `200` `Review`（`REJECTED`）；草稿解鎖，內容與 `revision` 不變 | `400`、`404` `REVIEW_NOT_FOUND`、`409` `REVIEW_NOT_PENDING` |
| POST | `/api/v1/journeys/{journey_id}/reviews/{review_id}/withdraw` | `{ "reason"? }` | `200` `Review`（`WITHDRAWN`）；草稿解鎖 | `403` `FORBIDDEN`（非送審者且非 `ADMIN`）、`404` `REVIEW_NOT_FOUND`、`409` `REVIEW_NOT_PENDING` |
| GET | `/api/v1/journeys/{journey_id}/reviews` | `cursor`、`limit`（1–100，預設 20） | `200` `Page<Review>`，依 `submitted_at` 新到舊（審核紀錄：誰、何時、原因） | `404` `JOURNEY_NOT_FOUND` |

- 狀態轉移：草稿（可編輯）—送審→ `PENDING` —核准→ `APPROVED`（新版本上線、草稿清除）／—退回→ `REJECTED`／—撤回→ `WITHDRAWN`；後兩者草稿回到可編輯，修改後再送審建立新的 `Review`
- 送審期間草稿鎖定（`PUT`／`DELETE` 草稿 → `409` `DRAFT_IN_REVIEW`）；要改內容先撤回。核准的內容一定等於送審時的 `revision`
- 一個旅程同時最多一筆 `PENDING`（DB 部分唯一索引，spec 07 §12）；核准、退回、撤回以「`status` 仍為 `PENDING`」為條件更新，兩位管理員同時處理時後到者得 `409` `REVIEW_NOT_PENDING`（重新載入）
- 旅程停止（§3.5）時，送審中的 `Review` 同交易轉 `WITHDRAWN`（`decided_by` = 停止者、`reason` = 「旅程已停止」）
- 送審、核准、退回、撤回只收 session（紀錄要對得到人）；Bearer 呼叫 → `403` `FORBIDDEN`。審核紀錄不可修改或刪除；使用者停用後紀錄照常保留

### §3.3 審核規則（D-060 待甲方決定；本節是唯一的差異點，甲方選 B 只改本節與 `auth.md` §2 對應列）

| # | 規則 | 本契約採用（建議 A） | 若選 B |
|---|---|---|---|
| 1 | 送審者能否核准自己的 `Review` | 不能：`decided_by` = `submitted_by` 的核准 → `403` `SELF_APPROVAL_FORBIDDEN`（退回、撤回不受限）；只有一位啟用中的 `ADMIN` 時，他送審的旅程須等增設第二位管理員才能核准（D-057 開通時建兩位） | 可以：拿掉此檢查與錯誤碼；紀錄照常有 `submitted_by`、`decided_by` |
| 2 | 哪些動作要審核 | 每次發佈（含已上線旅程發新版）都要（`action` = `PUBLISH`）；停止不審核，`EDITOR` 以上直接停（§3.5），紀錄在 `JourneyDetail.stopped_by`／`stopped_at`／`stop_reason` | 停止也要審核：§3.5 改為建立 `action` = `STOP` 的 `Review`，核准時才執行停止；`ReviewAction` 加 `STOP` |
| 3 | 管理金鑰經 journey-engine §3.1 直接建立版本（待決定，新） | 保留給維運與 demo-seed，不經審核；同交易寫一筆 `Review`（`APPROVED`、`submitted_by`／`decided_by` = `null`、`reason` = 「管理金鑰直接發佈」）留紀錄 | 金鑰也不可直接發佈：§3.1 第二版起回 `403` `FORBIDDEN`，旅程一律經草稿送審（demo-seed 改用兩個示範管理員送審＋核准） |

### §3.5 停止 `POST /api/v1/journeys/{journey_id}/stop`

- Request：`{ "reason" }`（1–200 字）；成功 `200` `JourneyDetail`（`status` = `ABORTED`、`stopped_by` = 呼叫者）；已停止再停 → `200`（不改原因）；`DRAFT` → `409` `JOURNEY_NOT_PUBLISHED`；是否需審核見 §3.3 第 2 列
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

`POST /api/v1/journeys/01JCXYZJOURNEY0000000001/reviews/01JD0REVIEW00000000000001/reject` `{ "reason": "等待時間請改為 2 天" }` → `200`

```json
{ "review_id": "01JD0REVIEW00000000000001", "journey_id": "01JCXYZJOURNEY0000000001", "action": "PUBLISH", "status": "REJECTED",
  "revision": 5, "base_version": 2, "note": null,
  "submitted_by": { "user_id": "01JD0USER0000000000000001", "display_name": "營運小組" }, "submitted_at": "2026-11-02T03:00:00Z",
  "decided_by": { "user_id": "01JD0USER0000000000000002", "display_name": "行銷主管" }, "decided_at": "2026-11-02T05:30:00Z",
  "reason": "等待時間請改為 2 天", "version": null }
```
