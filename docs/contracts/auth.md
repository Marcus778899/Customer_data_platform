# 契約：登入、使用者與角色（v2，v0.2.0；單一租戶 D-051）

<!-- 前後端唯一的真相。設計取捨見 docs/design/DESIGN-03.md；取代 journey-engine.md §1.1、§1.2（BE-06 合併起生效） -->

- Base URL、JSON 慣例、時間、`X-Request-Id`、錯誤格式：同 `journey-engine.md` §1、§5
- 跨域：不開 CORS（同源經 proxy）
- 單一租戶（D-051）：一套環境 = 一家公司；沒有工作區、沒有租戶標頭；`tenant_id` 同 `journey-engine.md` §1（請求 body 帶了一律忽略）

## §1 憑證

| 憑證 | 怎麼帶 | 角色 |
|---|---|---|
| 攝取金鑰（D-019） | `Authorization: Bearer <金鑰>`，金鑰 = 環境變數 `CDP_INGEST_API_KEY` | `INGEST` |
| 管理金鑰 | `Authorization: Bearer <金鑰>`，金鑰 = 環境變數 `CDP_ADMIN_API_KEY` | 等同使用者角色 `ADMIN`（腳本、demo-seed、開第一個帳號） |
| 使用者 session | cookie `CDP_SESSION`（§3 登入取得） | 該使用者目前的角色 `VIEWER`／`EDITOR`／`ADMIN` |

- 帶了 `Authorization` 就只看它、不看 cookie
- 先驗認證、再驗角色、最後驗輸入：未認證 `401` `UNAUTHENTICATED`；角色不足 `403` `FORBIDDEN`
- session 每個請求都以當下的使用者資料判斷：使用者被停用 → 下一個請求即 `401`；角色變更 → 下一個請求即生效

### §1.1 CSRF（session 的寫入請求）

- session 呼叫非 `GET` 的 API（`/auth/login`、`/auth/logout` 除外）須帶標頭 `X-XSRF-TOKEN`，值 = cookie `XSRF-TOKEN`（`Path=/`、非 HttpOnly、`Secure`、`SameSite=Strict`）；前端每次送出前讀 cookie 當下的值，不快取
- 登入、登出成功會使舊值失效（回應可能清除該 cookie）；前端登入成功後先呼叫 `GET /api/v1/auth/me`，其回應一定帶新的 `XSRF-TOKEN`；其他回應也可能更新它
- 缺或不符 → `403` `CSRF_INVALID`（前端重呼 `/auth/me` 取新值後重試一次）
- Bearer 請求不檢查 CSRF

## §2 角色矩陣（`✓` = 可用；`ADMIN` ⊇ `EDITOR` ⊇ `VIEWER`；管理金鑰同 `ADMIN`）

| API | `INGEST` | `VIEWER` | `EDITOR` | `ADMIN` |
|---|---|---|---|---|
| `POST /api/v1/events`、`/events/batch`（journey-engine §3.2） | ✓ | | | |
| `PUT /api/v1/profiles/{id}`（profiles §2） | ✓ | | | ✓（僅管理金鑰；session 不可） |
| `GET /api/v1/enrollments*`（journey-engine §4）、`GET /api/v1/profiles*`（profiles §3）、`GET /api/v1/journeys*`（journeys §2；第二版含草稿與審核紀錄） | | ✓ | ✓ | ✓ |
| `POST /api/v1/journeys`（journey-engine §3.1） | | | | ✓（僅管理金鑰；session 不可，D-056 發佈須經審核） |
| journeys §3 草稿寫入、`draft/validate`、`draft/submit`（送審）；§3.5 停止（D-060 建議 2A，見 journeys §3.3 第 2 列） | | | ✓ | ✓ |
| journeys §3.2 `withdraw`（撤回送審） | | | ✓（僅自己送審的） | ✓ |
| journeys §3.2 `approve`（核准）、`reject`（退回） | | | | ✓（session；不可核准自己送審的，D-060 建議 1A，見 journeys §3.3 第 1 列） || §4 `/api/v1/users*` | | | | ✓ |
| §3 `/api/v1/auth/*` | | session（不分角色） | | |

- 不需認證：`POST /api/v1/auth/login`、`POST /api/v1/auth/logout`；其他 `/api/**` 未列者：一律 `403`（已認證）／`401`（未認證）

## §3 登入

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | `{ "email", "password" }` 皆必填；`email` 不分大小寫 | `204`，`Set-Cookie: CDP_SESSION=…; Path=/api; HttpOnly; Secure; SameSite=Strict`（每次換新 id）與 `XSRF-TOKEN` | `400` `VALIDATION_FAILED`、`401` `INVALID_CREDENTIALS`（帳號不存在、停用、密碼錯一律同一碼）、`429` `LOGIN_RATE_LIMITED`（見下）、`415` |
| POST | `/api/v1/auth/logout` | — | `204`，清除 cookie；沒有 session 也回 `204` | — |
| GET | `/api/v1/auth/me` | — | `200` `CurrentUser` | `401`；`403`（Bearer 呼叫） |
| POST | `/api/v1/auth/password` | `{ "current_password", "new_password" }` | `204`；其他 session 不登出 | `400` `VALIDATION_FAILED`（密碼規則見下）、`401` `INVALID_CREDENTIALS`（舊密碼錯）、`403` `CSRF_INVALID` |

- `CurrentUser`：`user_id` string、`email` string、`display_name` string、`role` `VIEWER` \| `EDITOR` \| `ADMIN`
- 密碼規則：12–72 字元（UTF-8 位元組數 ≤ 72）；不檢查複雜度
- session 閒置逾時 = `CDP_SESSION_IDLE_TIMEOUT`；後端重啟 session 全失效
- 登入失敗限速（D-045）：
  | 項目 | 規則 |
  |---|---|
  | 計算維度 | 請求的 `email`（轉小寫）；帳號不存在也計數、回應與存在者相同；不依來源 IP |
  | 計數 | 密碼錯（`401` `INVALID_CREDENTIALS`）才 +1；`400` 不計；距上次失敗超過 `CDP_LOGIN_LOCKOUT` 先歸零再計；登入成功歸零 |
  | 門檻與鎖定 | 計數達 `CDP_LOGIN_MAX_FAILURES` → 鎖定到「最後一次失敗 + `CDP_LOGIN_LOCKOUT`」；鎖定中的嘗試不計數、不延長 |
  | 超限回應 | 鎖定中任何登入（含正確密碼）→ `429` `LOGIN_RATE_LIMITED`，標頭 `Retry-After` = 剩餘秒數（整數，無條件進位）；不跑 BCrypt |
  | 範圍 | 只限 `POST /auth/login`（`/auth/password` 不計）；計數存記憶體，後端重啟歸零（同 session） |
- 前端：任何 API 回 `401` `UNAUTHENTICATED` → 導向登入

## §4 使用者管理（`ADMIN`）

| Method | Path | Request | 成功 | 錯誤 |
|---|---|---|---|---|
| GET | `/api/v1/users` | — | `200` `{ "items": User[] }`，依 `email` 排序，含停用者 | — |
| POST | `/api/v1/users` | `{ "email", "display_name", "role", "password" }` | `201` `User` | `400`、`409` `EMAIL_TAKEN` |
| PATCH | `/api/v1/users/{user_id}` | `{ "display_name"?, "role"?, "status"?, "password"? }`（`status` = `ACTIVE`／`DISABLED`） | `200` `User` | `400`、`404` `USER_NOT_FOUND`、`409` `LAST_ADMIN`（會使啟用中的 `ADMIN` 變成 0 位） |

- `User`：`user_id`（ULID）、`email`（存小寫）、`display_name`（1–100 字）、`role`、`status`、`created_at`、`updated_at`
- 不提供刪除（停用即可；歷史紀錄的 `updated_by` 需要對得到人）
- 第一位 `ADMIN` 由維運以管理金鑰建立（帳號開通方式見 D-057）

## §5 範例

`POST /api/v1/auth/login` `{ "email": "ops@example.com", "password": "…" }` → `204`

`GET /api/v1/auth/me` → `200`

```json
{ "user_id": "01JD0USER0000000000000001", "email": "ops@example.com", "display_name": "營運小組", "role": "EDITOR" }
```

`PATCH /api/v1/users/01JD0USER0000000000000002`（唯一啟用中的 `ADMIN`）`{ "role": "VIEWER" }` → `409` `{ …, "code": "LAST_ADMIN", "detail": "至少要保留一位啟用中的管理員。" }`
