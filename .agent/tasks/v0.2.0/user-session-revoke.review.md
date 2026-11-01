APPROVE 6e55423

# Review user-session-revoke（PR #110）第 3 輪

上一輪 APPROVE b948d55。b948d55..6e55423 只有 sync develop（f856d68，deps-security-upgrade）。

| 檢查 | 結果 |
|---|---|
| 負責檔案在 b948d55..6e55423 有無變更（`git diff b948d55 6e55423 -- security/ user/ V7_1 acceptance/user-session-revoke/ 單元測試`） | 無變更；這段範圍只有 develop 帶進來的 `api/pom.xml`（Spring Boot 4.1.2） |
| `LoginSessionAcceptanceTest` 從任務 diff 消失 | `git diff origin/develop 6e55423 -- acceptance/user-auth-api/` 為空：develop 已含同一個修正（建 ADMIN 再降級），內容與本單 b948d55 相同，沒有遺失 |
| sync 後的 PR diff（10 個檔案） | 都在負責檔案、驗收測試範圍內；實作照 auth.md §1／§4 與 DESIGN-03 做法 #2（D-071）：只在 `PATCH /users/{id}` 帶 `password` 時 `session_epoch` +1（`UserService.update` 的 `newHash != null`），`changePassword` 傳 `false`（其他裝置不登出）；login 存 epoch，filter 不符或缺值就 invalidate（fail closed）；PATCH 錯誤時 transaction 不寫入 |
| migration | `V7_1` 只加欄位（expand，`NOT NULL DEFAULT 0`），註解寫 down；SQL 參數綁定 |
| Spring Boot 4.1.2 相容性 | 本單用到的 API（`JdbcClient`、`HttpSession`、`OncePerRequestFilter`）不受影響；qa 在 6e55423 跑全部測試 311 個通過，CI Check／Security 通過 |
| 寫死設定、安全 | 無新設定、無寫死值；session 屬性只存 user_id 與整數 epoch |
| 單元測試 | `UserServiceTest.update_bumps_session_epoch_only_when_password_is_given` 涵蓋 bump 的 true／false 分支 |

備註（不影響結論）：
- origin/develop 在 sync 後又合併了 #116（只改 `.github/workflows/ci.yml`），與本單檔案不重疊。
- 6e55423 還沒有 `V7__profile_store.sql`；合併仍須排在 profile-search-api 之後（派工單已寫），否則 Flyway 會因 V7 晚於 V7_1 而拒絕執行。
- 依指示沒跑含容器的測試，以 qa 證據（PASS @ 6e55423）為準。
