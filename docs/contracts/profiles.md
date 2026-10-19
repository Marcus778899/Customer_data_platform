# 契約：最小 ProfileStore 與客戶搜尋（v2，v0.2.0；單一租戶 D-051）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-03.md；表語意見 spec 07 §12 -->

- 通用規則（Base URL、JSON、時間、錯誤格式）：`journey-engine.md` §1、§5；憑證與角色：`auth.md` §1、§2
- 遮罩：同 `journey-engine.md` §4.4 的規則與 `CDP_TRACE_PLAIN_KEYS` 白名單
- 寫入來源：本版只有 §2 API 與事件攝取；§1.1 正規化、§2 合併與識別碼唯一是**所有**寫入來源共用的規則，之後經 PostgreSQL 的整合入口（D-052，DESIGN-04）寫入的結果必須與經 §2 寫入相同

## §1 資料模型

| 型別 | 欄位 | 型別 | 規則 |
|---|---|---|---|
| `IdentifierType` | — | enum | `EMAIL` `PHONE` `MEMBER_ID` `LINE_USER_ID` `DEVICE_ID`（同 spec 01 §3） |
| `Identifier` | `type` | `IdentifierType` | |
| | `value` | string | 寫入時見 §1.1 正規化；回應時依遮罩規則：鍵名 = `type` 小寫（例 `email`、`member_id`）在白名單才明文，否則遮罩 |
| | `masked` | bool | 僅回應有；`true` = `value` 為遮罩值 |
| `ProfileSummary` | `profile_id` | string | ULID |
| | `matched_by` | `IdentifierType` \| `PROFILE_ID` | 搜尋命中的方式 |
| | `identifiers` | `Identifier[]` | 依 `type`、`value` 排序 |
| | `created_at` `updated_at` | time | |
| `ProfileDetail` | （`ProfileSummary` 除 `matched_by` 外全部欄位） | | |
| | `attributes` | object | 客戶屬性，已遮罩（範圍鍵 `attributes`，規則同 journey-engine §4.4） |
| | `masked_paths` | string[] | 例 `attributes.birthday`、`identifiers[0].value` |
| | `as_of` | time | |

### §1.1 識別碼正規化（寫入與搜尋共用）

| `type` | 規則 | 不合法 |
|---|---|---|
| `EMAIL` | 去前後空白、轉小寫；須符合 `^[^@\s]+@[^@\s]+$` | `400` |
| `PHONE` | 去空白與 `-` `(` `)` `.`；`0` 開頭 → `+` ＋ 環境變數 `CDP_PHONE_DEFAULT_COUNTRY_CODE` ＋ 去掉開頭 `0` 的其餘數字；結果須符合 `^\+[1-9][0-9]{6,14}$` | `400` |
| 其他 | 去前後空白；1–200 字；區分大小寫 | `400` |

## §2 寫入 `PUT /api/v1/profiles/{profile_id}`

- Request：`{ "attributes"?: object, "identifiers"?: [ { "type", "value" } ] }`；`profile_id` 為 ULID（由呼叫端產生，與事件 `identity.profile_id` 同一套）
- 語意：不存在則建立。`attributes` 淺層合併：給的鍵覆寫，值為 `null` 刪除該鍵，未給的鍵保留；鍵符合 `^[a-z][a-z0-9_]*$`；合併後序列化 ≤ 32 KB。`identifiers` 只新增（已存在且屬於本客戶 = 不變）；v0.2.0 不提供移除
- 成功：`200` `ProfileDetail`（新建也回 `200`）
- 錯誤：`400` `VALIDATION_FAILED`（`errors[].field` 如 `identifiers[1].value`、`attributes.Name`）；`409` `IDENTIFIER_CONFLICT`（該識別碼已屬於另一位客戶；整筆不寫入，`detail` 不揭露對方 ID）
- 事件攝取（journey-engine §3.2）遇到不存在的 `profile_id` 時，後端自動建立空白客戶（無屬性、無識別碼）；v0.2.0 不做身分解析，`identity.profile_id` 仍必填

## §3 查詢

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| GET | `/api/v1/profiles/search` | `q`（必填，1–200 字） | `200` `{ "items": ProfileSummary[] }`，最多 20 筆，依 `matched_by`（`PROFILE_ID` 先）再 `updated_at` 新到舊；查無 = `[]` | `400` |
| GET | `/api/v1/profiles/{profile_id}` | — | `200` `ProfileDetail` | `404` `PROFILE_NOT_FOUND` |
| GET | `/api/v1/profiles/{profile_id}/enrollments` | `status`、`cursor`、`limit`（同 journey-engine §4 `/enrollments`） | `200` `Page<EnrollmentSummary>`，依 `enrolled_at` 新到舊 | `400`、`404` `PROFILE_NOT_FOUND` |

`q` 的比對（精確比對、不做模糊或前綴；全部候選取聯集、同一客戶只列一次，`matched_by` 取下列最先命中者）：

1. 符合 ULID（不分大小寫，比對時轉大寫）→ `profile_id`
2. 含 `@` → 依 §1.1 正規化後比 `EMAIL`
3. 可依 §1.1 正規化成 `PHONE` → 比 `PHONE`
4. 原字串去前後空白 → 比 `MEMBER_ID`、`LINE_USER_ID`、`DEVICE_ID`

## §4 範例

`GET /api/v1/profiles/search?q=Amy@Example.com`（`CDP_TRACE_PLAIN_KEYS=member_id`）→ `200`

```json
{ "items": [ { "profile_id": "01JCXYZPROFILE00000000001", "matched_by": "EMAIL",
  "identifiers": [ { "type": "EMAIL", "value": "a***@example.com", "masked": true },
                   { "type": "MEMBER_ID", "value": "M-10023", "masked": false } ],
  "created_at": "2026-10-18T02:00:00Z", "updated_at": "2026-11-01T08:00:00Z" } ] }
```

`PUT /api/v1/profiles/01JCXYZPROFILE00000000002`（攝取金鑰）`{ "identifiers": [ { "type": "EMAIL", "value": "amy@example.com" } ] }` → `409` `IDENTIFIER_CONFLICT`
