# 契約：多租戶、登入、角色與工作區（v1，v0.2.0）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-03.md；取代 journey-engine.md §1.1、§1.2（BE-07 合併起生效） -->

- Base URL、JSON 慣例、時間、`X-Request-Id`、錯誤格式：同 `journey-engine.md` §1、§5
- 跨域：不開 CORS（同源經 proxy）
- 名詞：**租戶**＝工作區（workspace）；**使用者**可屬於多個租戶，每個租戶一個角色

## §1 憑證與租戶來源

| 憑證 | 怎麼帶 | 租戶從哪來 | 角色 |
|---|---|---|---|
| 平台金鑰 | `Authorization: Bearer <金鑰>`，金鑰 = 環境變數 `CDP_PLATFORM_API_KEY` | 不屬於任何租戶；只能呼叫 §5 | `PLATFORM` |
| 租戶金鑰 | `Authorization: Bearer <金鑰>`，由 §5.4 發放 | 金鑰所屬租戶；`X-Tenant-Id` 會被忽略 | `INGEST` 或 `ADMIN`（金鑰的 `kind`） |
| 使用者 session | cookie `CDP_SESSION`（§3 登入取得） | 請求標頭 `X-Tenant-Id`（必填於 §2 標「租戶」的 API），須是該使用者的成員租戶 | 該租戶的成員角色 `VIEWER`／`EDITOR`／`TENANT_ADMIN` |

- 帶了 `Authorization` 就只看它、不看 cookie
- 先驗認證、再驗租戶、再驗角色、最後驗輸入：未認證 `401` `UNAUTHENTICATED`；session 呼叫租戶 API 缺 `X-Tenant-Id` → `400` `TENANT_REQUIRED`；不是成員或租戶已停用 → `403` `FORBIDDEN`；角色不足 → `403` `FORBIDDEN`
- 租戶已停用（`DISABLED`）：該租戶的金鑰一律 `401`；`/auth/me` 不列出該租戶
- 請求 body 帶 `tenant_id` 一律忽略

### §1.1 CSRF（session 的寫入請求）

- session 呼叫非 `GET` 的 API（`/auth/login`、`/auth/logout` 除外）須帶標頭 `X-XSRF-TOKEN`，值 = cookie `XSRF-TOKEN`（`Path=/`、非 HttpOnly、`Secure`、`SameSite=Strict`）；前端每次送出前讀 cookie 當下的值，不快取
- 登入、登出成功會使舊值失效（回應可能清除該 cookie）；前端登入成功後先呼叫 `GET /api/v1/auth/me`，其回應一定帶新的 `XSRF-TOKEN`；其他回應也可能更新它
- 缺或不符 → `403` `CSRF_INVALID`（前端重呼 `/auth/me` 取新值後重試一次）
- Bearer 請求不檢查 CSRF

## §2 角色矩陣（`✓` = 可用；角色由上到下包含：`TENANT_ADMIN` ⊇ `EDITOR` ⊇ `VIEWER`）

| API | 租戶 | `INGEST` | `ADMIN` 金鑰 | `VIEWER` | `EDITOR` | `TENANT_ADMIN` | `PLATFORM` |
|---|---|---|---|---|---|---|---|
| `POST /api/v1/events`、`/events/batch`（journey-engine §3.2） | ✓ | ✓ | | | | | |
| `PUT /api/v1/profiles/{id}`（profiles §2） | ✓ | ✓ | ✓ | | | | |
| `GET /api/v1/enrollments*`（journey-engine §4）、`GET /api/v1/profiles*`（profiles §3）、`GET /api/v1/journeys*`（journeys §2） | ✓ | | ✓ | ✓ | ✓ | ✓ | |
| `POST /api/v1/journeys`（journey-engine §3.1）、journeys §3 全部寫入 | ✓ | | ✓ | | ✓ | ✓ | |
| `GET /api/v1/members`（§4） | ✓ | | | | | ✓ | |
| §3 `/api/v1/auth/*` | | | | session（不分角色） | | | |
| §5 `/api/v1/platform/*` | | | | | | | ✓ |

- 不需認證：`POST /api/v1/auth/login`、`POST /api/v1/auth/logout`；其他 `/api/**` 未列者：一律 `403`（已認證）／`401`（未認證）

## §3 登入與工作區

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | `{ "email", "password" }` 皆必填；`email` 不分大小寫 | `204`，`Set-Cookie: CDP_SESSION=…; Path=/api; HttpOnly; Secure; SameSite=Strict`（每次換新 id）與 `XSRF-TOKEN` | `400` `VALIDATION_FAILED`、`401` `INVALID_CREDENTIALS`（帳號不存在、停用、密碼錯一律同一碼）、`415` |
| POST | `/api/v1/auth/logout` | — | `204`，清除 cookie；沒有 session 也回 `204` | — |
| GET | `/api/v1/auth/me` | — | `200` `CurrentUser` | `401`；`403`（Bearer 呼叫） |
| POST | `/api/v1/auth/password` | `{ "current_password", "new_password" }` | `204`；其他 session 不登出 | `400` `VALIDATION_FAILED`（新密碼規則見下）、`401` `INVALID_CREDENTIALS`（舊密碼錯）、`403` `CSRF_INVALID` |

- `CurrentUser`：`user_id` string、`email` string、`display_name` string、`workspaces` `Workspace[]`（依 `name` 排序；可為 `[]` = 沒有任何租戶可用）
- `Workspace`：`tenant_id` string、`name` string、`role` `VIEWER` \| `EDITOR` \| `TENANT_ADMIN`
- 密碼規則：12–72 字元（UTF-8 位元組數 ≤ 72）；不檢查複雜度
- session 閒置逾時 = `CDP_SESSION_IDLE_TIMEOUT`；後端重啟 session 全失效；session 不記住「目前租戶」，每個請求以 `X-Tenant-Id` 指定（多分頁可各開不同工作區）
- 前端：任何 API 回 `401` `UNAUTHENTICATED` → 導向登入；`X-Tenant-Id` 取自前端網址的工作區（前端路由由 frontend 定）

## §4 租戶成員（唯讀）

`GET /api/v1/members` → `200` `{ "items": Member[] }`；`Member`：`user_id` `email` `display_name` string、`role`、`added_at` time。v0.2.0 不提供租戶內新增成員（由 §5 開通）。

## §5 平台 API（只收平台金鑰）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/platform/tenants` | `{ "name" }`（1–100 字） | `201` `Tenant` | `400` |
| GET | `/api/v1/platform/tenants` | — | `200` `{ "items": Tenant[] }` | — |
| PATCH | `/api/v1/platform/tenants/{tenant_id}` | `{ "name"?, "status"? }` | `200` `Tenant` | `400`、`404` `TENANT_NOT_FOUND` |
| POST | `/api/v1/platform/users` | `{ "email", "display_name", "password" }` | `201` `{ "user_id", "email", "display_name", "status" }` | `400`、`409` `EMAIL_TAKEN` |
| PATCH | `/api/v1/platform/users/{user_id}` | `{ "display_name"?, "password"?, "status"? }`（`ACTIVE`／`DISABLED`） | `200` 同上 | `400`、`404` `USER_NOT_FOUND` |
| PUT | `/api/v1/platform/tenants/{tenant_id}/members/{user_id}` | `{ "role" }` | `200` `Member`（新增或改角色） | `400`、`404` `TENANT_NOT_FOUND`／`USER_NOT_FOUND` |
| DELETE | `/api/v1/platform/tenants/{tenant_id}/members/{user_id}` | — | `204`（不存在也 `204`） | `404` `TENANT_NOT_FOUND` |
| POST | `/api/v1/platform/tenants/{tenant_id}/api-keys` | `{ "kind": "INGEST" \| "ADMIN", "label" }` | `201` `ApiKey` ＋ `secret`（只在此回應出現一次） | `400`、`404` `TENANT_NOT_FOUND` |
| GET | `/api/v1/platform/tenants/{tenant_id}/api-keys` | — | `200` `{ "items": ApiKey[] }`（含已撤銷） | `404` `TENANT_NOT_FOUND` |
| DELETE | `/api/v1/platform/tenants/{tenant_id}/api-keys/{key_id}` | — | `204`（撤銷，立即失效；重複撤銷也 `204`） | `404` `TENANT_NOT_FOUND`／`API_KEY_NOT_FOUND` |

- `Tenant`：`tenant_id`（ULID）、`name`、`status` `ACTIVE` \| `DISABLED`、`created_at`
- `ApiKey`：`key_id`、`kind`、`label`、`prefix`（`secret` 前 12 字，供辨識）、`created_at`、`revoked_at` time \| null
- `secret` 格式 `cdp_<kind 小寫>_<43 字 base64url>`；後端只存雜湊，遺失只能重發

## §6 範例

`POST /api/v1/auth/login` `{ "email": "ops@example.com", "password": "…" }` → `204`

`GET /api/v1/auth/me` → `200`

```json
{ "user_id": "01JD0USER0000000000000001", "email": "ops@example.com", "display_name": "營運小組",
  "workspaces": [ { "tenant_id": "01JD0TENANT00000000000001", "name": "品牌 A", "role": "EDITOR" },
                  { "tenant_id": "01JD0TENANT00000000000002", "name": "品牌 B", "role": "VIEWER" } ] }
```

`GET /api/v1/enrollments?q=…`（session，缺 `X-Tenant-Id`）→ `400` `{ …, "code": "TENANT_REQUIRED", "detail": "請指定工作區。" }`

`POST /api/v1/platform/tenants/01JD0TENANT00000000000001/api-keys`（平台金鑰）`{ "kind": "INGEST", "label": "官網 SDK" }` → `201`

```json
{ "key_id": "01JD0KEY000000000000000001", "kind": "INGEST", "label": "官網 SDK", "prefix": "cdp_ingest_A",
  "created_at": "2026-11-02T03:00:00Z", "revoked_at": null, "secret": "cdp_ingest_A1b2…" }
```
