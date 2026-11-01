APPROVE 7a0c69f

# Review deps-security-upgrade（PR #117，HEAD 7a0c69f）

上一輪已 APPROVE 27cb351（pom.xml 升版）；本輪只看新增的 e7fc134、7a0c69f，並重新確認整體 diff。

| 項目 | 檢查 | 結果 |
|---|---|---|
| 整體 diff 範圍 | `gh pr diff 117`：只有 `api/pom.xml`（parent 4.1.1→4.1.2、`rabbit-amqp-client.version` 5.37.0）與 `LoginSessionAcceptanceTest.java` +2 行 | 符合負責檔案與檔尾追加的驗收測試；未碰 `api/src/main`、`.github/`、Makefile、掃描忽略設定 |
| e7fc134 測試修正 | 在 `role_change_takes_effect_on_next_request` 開頭加註解與 `AuthHttp.createUserId(port, uniqueEmail(), "ADMIN");`，其餘斷言不變 | 照仲裁「測試應該怎麼改」第 1 點；確保降級時另有啟用中的 ADMIN，符合 `docs/contracts/auth.md` §4 PATCH `409 LAST_ADMIN` 的前提，測試不再依賴類別執行順序 |
| 與 user-session-revoke 一致 | `git diff 7a0c69f feat/user-session-revoke -- LoginSessionAcceptanceTest.java` 無輸出；PR diff 的 blob `53e945b..1ddff74` 與 qa 證據一致 | 逐字相同，之後 sync 不會衝突 |
| 7a0c69f | `git show --stat` 無檔案變更 | 空 commit（D-076），無程式變動 |
| 寫死設定、安全 | 新增行只有測試資料建立，沒有 secret／port／網址 | 無問題 |
| CI | `gh pr checks 117`：Check pass、Security pass（run 41108224055） | Security 綠燈滿足驗收條件 2；Check 綠燈含原本失敗的 role_change 測試 |

未跑含容器的測試（依指示）；後端全部測試 297 個通過的證據見 `.agent/reports/deps-security-upgrade.qa.md`。

問題：0 個。
