# 派工單 user-session-revoke

- **類型**：feat
- **負責**：backend
- **目標**：實作 D-071：管理員重設密碼（`PATCH /users/{id}` 帶 `password`）使該使用者所有 session 失效
- **背景**：設計見 DESIGN-03 做法 #2 的 D-071 段與派工順序批 4（PR #107）。可與 profile-search-api 平行開發，但合併必須排在它之後（V7_1 在 V7 之後，Flyway 不允許亂序）。spec 07 §12 的 migration 表與 `app_user` 欄位表尚未補 V7_1 與 `session_epoch`，併入本單
- **設計依據**：`docs/contracts/auth.md` §1、§3 `/auth/password`、§4 PATCH；`docs/design/DESIGN-03.md` 做法 #2、派工順序批 4；`docs/decisions.md` D-071
- **相依**：開發無；合併等 profile-search-api 合併
- **負責檔案**：
  - `api/src/main/java/com/cdp/security/SessionAuthenticationFilter.java`、`api/src/main/java/com/cdp/security/AuthController.java`
  - `api/src/main/java/com/cdp/user/`
  - `api/src/main/resources/db/migration/V7_1__app_user_session_epoch.sql`
  - `docs/spec/07-database-schema.md`（只補 §12 的 V7_1 與 `app_user.session_epoch`）
  - `api/src/test/java/com/cdp/security/`、`api/src/test/java/com/cdp/user/`
- **禁區**：profile-search-api 的負責檔案；不可改驗收測試；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/user-session-revoke/`
- **驗收條件**：
  - [ ] 管理員（session 或管理金鑰）`PATCH /users/{id}` 帶 `password` 成功後，該使用者所有既有 session 下一個請求回 401
  - [ ] 管理員重設自己的密碼：仍回 200，自己目前的 session 隨後失效
  - [ ] 只改其他欄位、或 PATCH 回錯誤時，session 不受影響
  - [ ] 使用者自己 `/auth/password` 改密碼，其他裝置不登出
  - [ ] 既有驗收全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/user-session-revoke.json`

## 執行資訊（task.py 產生）

- Issue：#109
- 分支：`feat/user-session-revoke`（從 develop 開出）
- 工作目錄：`.worktrees\user-session-revoke`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-30

CI 在 sync（5937038）後連兩次失敗：`user-auth-api/LoginSessionAcceptanceTest::role_change_takes_effect_on_next_request` 第 320 行把使用者降回 VIEWER 得到 409 `LAST_ADMIN`。該測試假設資料庫裡另有啟用中的 ADMIN，結果取決於其他測試的執行順序。依甲方決定在本單內修正（仲裁見 `reports/user-session-revoke.ruling.md`），驗收測試範圍追加該檔：

- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/user-session-revoke/`
  - `api/src/test/java/com/cdp/acceptance/user-auth-api/LoginSessionAcceptanceTest.java`
