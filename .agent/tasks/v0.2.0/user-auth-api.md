# 派工單 user-auth-api

- **類型**：feat
- **負責**：backend
- **目標**：依 `auth.md` 實作使用者登入（email＋密碼，BCrypt）、角色（VIEWER／EDITOR／ADMIN）、CSRF、登入限速與 `/users` 管理 API，取代 v0.1.0 的共用客服帳密
- **背景**：即 DESIGN-03 派工表的 BE-06（驗收為 QA-04）。D-056 三種角色、D-057 首位管理員以管理金鑰經 API 建立、D-045 登入限速、D-020 共用帳密廢除。前端（app-shell-login）已依 `auth.md` 對 mock 完成。既有驗收中用客服帳密登入的部分由 qa 在本分支依 `auth.md` 改寫，worker 不可改。測試檔 package 宣告用 `com.cdp.acceptance.userauth`。
- **設計依據**：`docs/contracts/auth.md`（全部）；`docs/design/DESIGN-03.md` 做法 #2、#3、「設定」；`docs/spec/07-database-schema.md` §12（`app_user`、V6）；`docs/contracts/journey-engine.md` §1、§5
- **相依**：relay-loop-rewrite（已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/security/`、`api/src/main/java/com/cdp/user/`（新）
  - `api/src/main/resources/db/migration/V6__app_user.sql`（新）
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`
  - `tools/demo-seed/`
  - `.env.example`（刪 `CDP_SUPPORT_USERNAME`、`CDP_SUPPORT_PASSWORD`；限速兩變數已在）
  - `api/src/test/java/com/cdp/` 中對應上述類別的單元測試（`acceptance/` 除外）
- **禁區**：`api/src/test/java/com/cdp/acceptance/`；V1～V5 migration；其他 API 的行為與回應格式；`web/`、`docs/`；不加新相依（BCrypt 用 starter 內的 `spring-security-crypto`）；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/user-auth-api/`
  - `api/src/test/java/com/cdp/acceptance/security/`（qa 改寫的部分）
- **驗收條件**：
  - [ ] V6 只建 `app_user`，不動既有表；`auth/login` 改 email＋密碼，帳號不存在也跑一次 BCrypt；session 只存 `user_id`，每請求重新載入使用者（停用、改角色下一請求生效）
  - [ ] `auth.md` §2 角色矩陣放行／拒絕正確（含 Bearer 不看 cookie、未列路徑 401／403）；`RoleHierarchy` ADMIN > EDITOR > VIEWER
  - [ ] CSRF 依 §1.1（`csrf.spa()`、`SameSite=Strict`；Bearer 與 login／logout 免檢查；`CSRF_INVALID`）
  - [ ] 登入限速依 §3 表格（email 維度、`Retry-After` 無條件進位、鎖定中不跑 BCrypt、成功歸零）
  - [ ] `/users`：建立、列出、改角色／狀態／密碼；`EMAIL_TAKEN`；`LAST_ADMIN` 與更新同一交易（`SELECT … FOR UPDATE`）
  - [ ] demo-seed 以管理金鑰經 `POST /users` 建三種角色的示範帳號；程式不再讀 `CDP_SUPPORT_*`
  - [ ] 驗收測試全綠；既有測試全綠（`docker compose --env-file .env -f .worktrees/user-auth-api/compose.yaml run --rm test`）
- **驗證指令**：`make check`；`docker compose --env-file .env -f .worktrees/user-auth-api/compose.yaml run --rm test`
- **回報檔**：`reports/user-auth-api.json`

## 執行資訊（task.py 產生）

- Issue：#103
- 分支：`feat/user-auth-api`（從 develop 開出）
- 工作目錄：`.worktrees\user-auth-api`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-22

## 驗收測試範圍補充（qa 已在本分支 commit f50659a）

qa 依 `auth.md` 改寫了以下既有驗收（只改登入方式與建帳號，意圖不變），同列為本單驗收測試，worker 不可改：
  - `api/src/test/java/com/cdp/acceptance/query/`、`ingest/`、`relay-loop-rewrite/`（以及派工單已列的 `security/`、`user-auth-api/`）

qa 的假設（實作請配合，不符就回報 disputes，不要改測試）：
1. 限速設定屬性：測試同時設了 `cdp.login.max-failures`／`cdp.login.lockout` 與 `cdp.security.login-max-failures`／`cdp.security.login-lockout`，實作採其中一組即可
2. 達到門檻的那一次失敗仍回 401，之後才回 429
3. 登入請求不再接受 `username`（缺 `email` → 400）
4. `SecretToStringAcceptanceTest` 假設 `com.cdp.security.LoginRequest`（`{"email","password"}`）與 `AuthProperties`（`cdp.security.*`）仍存在
5. `application-test.yml` 的 `support-*` 由 worker 刪除
6. 「鎖定中不跑 BCrypt」沒有驗收測試，請寫單元測試覆蓋

## 追加 2026-10-22

## 範圍修正（manager 追認，取代上方「負責檔案」「驗收測試」）

worker 為回 `auth.md` 的錯誤碼在 `ErrorCode.java` 新增 5 個列舉值（既有不動），追認列入負責檔案；qa 改寫的既有驗收目錄正式列入驗收測試（受 lock 保護）。

- **負責檔案**：
  - `api/src/main/java/com/cdp/security/`、`api/src/main/java/com/cdp/user/`
  - `api/src/main/java/com/cdp/common/web/ErrorCode.java`（只新增 `auth.md` 的錯誤碼）
  - `api/src/main/resources/db/migration/V6__app_user.sql`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`
  - `tools/demo-seed/`
  - `.env.example`
  - `api/src/test/java/com/cdp/` 中對應上述類別的單元測試（`acceptance/` 除外）
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/user-auth-api/`
  - `api/src/test/java/com/cdp/acceptance/security/`
  - `api/src/test/java/com/cdp/acceptance/query/`
  - `api/src/test/java/com/cdp/acceptance/ingest/`
  - `api/src/test/java/com/cdp/acceptance/relay-loop-rewrite/`
