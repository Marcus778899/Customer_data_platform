PASS b88b7e0

# user-auth-api QA（PR #104，commit b88b7e0）

## 測試
指令：`docker compose --env-file .env -f .worktrees/user-auth-api/compose.yaml run --rm test`（Docker 正常，Testcontainers 可用）

結果摘要：`[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`

驗收測試目錄全綠（Failures 0、Errors 0）：
- userauth：LoginRateLimit 11、Csrf 9、SchemaV6AppUser 3、UsersApi 12、RoleMatrix 8、LoginSession 19、LastAdmin 9
- security：Authentication 16、EnrollmentsPath 3、SecretToString 4、LogoutCookieConfig 1、StartupValidation 3、NoGeneratedPasswordLog 1、MeEndpointRole 3、DefaultDeny 6
- query：EnrollmentQuery 23、NulParameter 7、PlainKeys 3 檔
- ingest：DispatchPropertiesStartup 6、NulCharacterValidation 4、EnrollmentWakeMissingData 2、EventDispatchRetry 5
- relayloop：RelayStartupValidation 5、RelayStuckTimers 1、RelayRewrite 7

輸出中的 `APPLICATION FAILED TO START`（DispatchProperties 綁定）是 DispatchPropertiesStartupAcceptanceTest 預期的負面案例，測試本身通過。

## Lint／前端
指令：`make check` -> `check passed`（web vitest 16 檔 196 測試通過）。

## 驗收條件對照
- V6／login／session／每請求重載、角色矩陣、CSRF、限速、/users 與 LAST_ADMIN：對應 userauth、security 驗收全綠。
- 既有測試全綠：297 通過。
- demo-seed／`CDP_SUPPORT_*`：`grep SUPPORT_` 在 api/src/main、tools、.env.example 無結果。
- 無寫死的 port／token／密碼（grep 無結果）。
- 驗收測試目錄自 f50659a 後無變更（git diff --stat 空）；worktree 乾淨。

## review 修正（b88b7e0）確認
- 登入 email 長度上限 254：`AuthController` 有 EMAIL_MAX 檢查，超過回 400 VALIDATION_FAILED。
- 登出 cookie secure 跟設定：`.secure(sessionCookie.getSecure() == null || ...)`；LogoutCookieConfigAcceptanceTest 通過。
- 過長密碼時間一致、限速原子化：對外行為由 LoginRateLimitAcceptanceTest／AuthenticationAcceptanceTest 驗證通過；原子化與 BCrypt 次數屬單元測試範圍（含於 297 測試中，全綠）。

## 備註（非 FAIL）
- 未實際啟動服務手動打 API（本單非整合單，驗收測試已以 SpringBootTest／Testcontainers 打真實 HTTP 與 DB）。
- D-071 不在本輪範圍。
