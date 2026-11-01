PASS a3f1ab7

# QA auth-session-design（PR #107，docs）

## 範圍
- `git diff develop...HEAD --stat`：只有 `docs/contracts/auth.md`（+7/-5）、`docs/design/DESIGN-03.md`（+4/-4）；皆在負責檔案內，無程式碼、migration、其他契約
- `git status --short`（worktree）：乾淨

## make check（自己在 worktree 執行）
```
Tests  196 passed (196)
check passed
exit code 0
```

## 驗收條件
1. §3 併發 429：auth.md §3 限速表新增「併發」列（同 email 計數＋進行中達門檻、未鎖定 → 429 `LOGIN_RATE_LIMITED`、`Retry-After: 1`、不計數不跑 BCrypt）。對照 `LoginRateLimiter.tryBegin`（`failures + inFlight >= maxFailures` 且未鎖定 → `Duration.ofSeconds(1)`）一致。PASS
2. §2 末條與 DESIGN-03 #2 CSRF：
   - §2 末條「未認證 401；INGEST/VIEWER/EDITOR 403；ADMIN 含管理金鑰交給路由 404/405」對照 `SecurityConfig` `.anyRequest().access(admin)` 一致
   - §1.1「未認證不檢查 CSRF、回 401；只帶標頭無 cookie 也算不符」對照 `sessionWriteRequest`（僅 session 認證的非 GET、排除 login/logout）與 `SessionCsrfTokenRepository` 一致
   - DESIGN-03 #2：`csrf.spa()` + `SessionCsrfTokenRepository`、`rotate` 只在登入與 `GET /auth/me`（AuthController 114、140 行）、`requireCsrfProtectionMatcher`、`secure` 欄位，皆與程式一致
   - §1 順序「認證 → CSRF → 角色 → 輸入」符合 CsrfFilter 位於授權之前。PASS
3. D-071：與 docs/decisions.md D-071（管理員重設密碼 → 該使用者所有 session 失效；自己改密碼不登出其他裝置）一致。auth.md §1（session 判斷列）、§3 `/auth/password` 註記、§4 PATCH 備註（含管理金鑰、重設自己時自己的 session 也失效且本次回 200、只改其他欄位或錯誤時不失效）皆已寫。PASS
4. DESIGN-03 派工表新增批 4 `user-session-revoke＋qa`：負責檔案（SessionAuthenticationFilter、AuthController、user/、`V7_1__app_user_session_epoch.sql`）、migration 編號 7.1（避開 BE-07 的 V7；現有 migration 最新 V6，spec 07 的 V8 未被佔用）、驗收目錄 `user-session-revoke/`、相依（可與 BE-07 平行、合併在其後）皆寫明。task_id 符合 `<功能>-<動作>`。PASS
5. 「需調查」「待決定」分開列：DESIGN-03 末段「待決定：無」「需調查：1 …」；回報 open_questions 亦分列。PASS

## 其他檢查
- DESIGN-03 行數 60，未超過回報所稱
- 無寫死設定值（port、網址、token）；未讀 .env

## 觀察（不構成 FAIL）
- DESIGN-03 `git diff` 刪了兩處空行（「派工順序…」標題前、「風險」標題前），純排版，不影響內容
- 回報所列後續事項：`docs/spec/07-database-schema.md` §12 migration 表與 app_user 欄位尚未補 V7_1／`session_epoch`（不在本單負責檔案，建議併進 user-session-revoke 或另開 docs 單）；manager 需排入
