PASS 7a0c69f

# QA 驗證 deps-security-upgrade（PR #117，HEAD 7a0c69f）

## 變動範圍
- `git diff 27cb351 HEAD --stat`：只有 `LoginSessionAcceptanceTest.java` +2 行（e7fc134 測試修正）；7a0c69f 是空 commit。程式與 pom 與上一輪 PASS 的 27cb351 相同。
- `api/pom.xml` 相對 develop 只有：parent 4.1.1 -> 4.1.2，properties 加 `rabbit-amqp-client.version` 5.37.0（只限版本，未動忽略規則、`.github/`、`api/src/main`）。

## role_change_takes_effect_on_next_request 修正與 user-session-revoke 分支逐字相同
- 本分支 `git diff 27cb351 HEAD -- LoginSessionAcceptanceTest.java` 與 feat/user-session-revoke 相對其 merge-base 的 diff 完全一致（兩者皆為 `index 53e945b..1ddff74`，同一 blob，內容含同一行註解與 `AuthHttp.createUserId(port, uniqueEmail(), "ADMIN");`）。
- `git diff HEAD feat/user-session-revoke -- 該檔` 無輸出。

## 後端全部測試
`docker compose --env-file .env -f .worktrees/deps-security-upgrade/compose.yaml run --rm test`
```
[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
（Spring Boot v4.1.2、Tomcat 11.0.27、spring-amqp 4.1.2；log 中的 InjectedFault／例外堆疊是故障注入測試的預期輸出。未遇容器環境錯誤，一次通過。）

## CI（PR #117 最新 commit 7a0c69f，run 41108224055）
```
Check     pass  7m21s
Security  pass  1m50s
```
Security 綠燈即驗收條件 2 的依據（osv-scanner 無漏洞）。

## 問題
無（0 個）。
