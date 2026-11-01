# 派工單 deps-security-upgrade

- **類型**：chore
- **負責**：backend
- **目標**：把 CI 資安掃描（osv-scanner）抓到的後端相依漏洞全部升到修正版，讓 CI 的 Security job 轉綠（D-074）
- **背景**：PR #110 的 CI（run 41091268596，Security job 124185449232）osv-scanner 報 `api/pom.xml` 21 個已知漏洞（4 Critical、9 High）：tomcat-embed-core 11.0.24→≥11.0.25、com.rabbitmq:amqp-client 5.30.0→≥5.36.1、tools.jackson.core:jackson-core／jackson-databind 3.1.5→≥3.1.7。這些都是 Spring Boot 4.1.1 parent 管理的傳遞相依；所有 PR 因此無法合併。可用 `gh api repos/Marcus778899/Customer_data_platform/actions/jobs/124185449232/logs` 看完整清單
- **設計依據**：`docs/design/INIT-01.md`（技術棧）；D-009（相依由團隊安排）、D-074
- **相依**：無
- **負責檔案**：
  - `api/pom.xml`（只限版本：優先升 Spring Boot parent 的 patch／minor 版；沒有涵蓋修正版的才用 properties 覆寫個別版本）
- **禁區**：`api/src/`（升版若需要改程式先回報，不自行改）；`.github/`、Makefile、`.gitleaks.toml`、osv-scanner 忽略設定一律不改（不可用忽略規則讓掃描通過）；`web/`、`package.json`；不可讀 .env
- **驗收測試**：無（chore；既有測試全部要過）
- **驗收條件**：
  - [ ] 上列 4 個套件的解析版本都 ≥ 修正版（附 `mvnw dependency:tree` 相關行或等效證據）
  - [ ] 本機用 osv-scanner 或等效方式掃 `api/pom.xml` 無 Critical／High；若無法本機掃，PR 的 CI Security job 綠燈為準
  - [ ] 後端全部測試（含驗收）通過：`docker compose --env-file .env -f .worktrees/deps-security-upgrade/compose.yaml run --rm test`，附輸出摘要
  - [ ] 回報列出改了哪些版本、Spring Boot 升版的 release notes 中與本專案相關的破壞性變更（沒有就寫沒有）
- **驗證指令**：`docker compose --env-file .env -f .worktrees/deps-security-upgrade/compose.yaml run --rm test`；`make lint`
- **回報檔**：`.agent/reports/deps-security-upgrade.json`

## 執行資訊（ae task 產生）

- Issue：#113
- 分支：`chore/deps-security-upgrade`（從 develop 開出）
- 工作目錄：`.worktrees\deps-security-upgrade`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-10-30

### .agent/reports/user-session-revoke.ruling.md

TEST_WRONG 5937038

驗收測試 lock 檢查：跟 lock（commit 5937038）時一樣，沒被改過

依據：`docs/contracts/auth.md` §4 PATCH（`409` `LAST_ADMIN`：會使啟用中的 `ADMIN` 變成 0 位）與 §4 範例（唯一啟用中的 `ADMIN` 降為 `VIEWER` → `409`）；CI run 41054164223 兩次執行（job 124057360904、124060408623）皆在同一斷言失敗，同一份程式碼（8ab85dd）在 run 40068392398 通過。

## 判定

| 測試名稱 | 契約條文 | 判定與理由 |
|---|---|---|
| `acceptance/user-auth-api/LoginSessionAcceptanceTest::role_change_takes_effect_on_next_request` | auth.md §4 PATCH `409 LAST_ADMIN` | 測試錯。測試把新使用者升為 ADMIN 再降回 VIEWER，並斷言降級回 `200`；依契約只有在另有啟用中的 ADMIN 時才成立。系統沒有預設 ADMIN 使用者，是否另有 ADMIN 取決於其他測試（`LastAdminAcceptanceTest`、`SessionRevokeAcceptanceTest` 等會建立、降級、停用 ADMIN）先執行並留下的狀態，測試類別執行順序一變，該使用者就是唯一的 ADMIN，實作依契約正確回 `409`。與本單實作無關 |

## 測試應該怎麼改（qa）

1. `role_change_takes_effect_on_next_request`：開頭先用 `AuthHttp.createUserId(port, uniqueEmail(), "ADMIN")` 建一位測試自己持有的啟用中 ADMIN，讓降級不受其他測試留下的狀態影響；其餘斷言不變

## 解開 CI 互卡（manager）

PR #117 的 CI：Security pass，Check 唯一失敗是 develop 既有的 `LoginSessionAcceptanceTest.role_change_takes_effect_on_next_request`（受測試類別執行順序影響）。這個測試已由 user-session-revoke 的仲裁判定 TEST_WRONG（上方附仲裁原文），修正只在 user-session-revoke 的分支上；但 user-session-revoke 要等本單合併後 Security 才會過，兩張互卡。因此把同一個修正放進本單：

- **驗收測試**：`api/src/test/java/com/cdp/acceptance/user-auth-api/LoginSessionAcceptanceTest.java`
- qa「A」：照仲裁「測試應該怎麼改」第 1 點改寫，內容與 user-session-revoke 分支上的修正逐字相同（測試開頭先 `AuthHttp.createUserId(port, uniqueEmail(), "ADMIN");` 並附同一行註解），其餘斷言不變；這樣 user-session-revoke 之後 sync 不會衝突
- worker：程式不用改；qa 改完、lock 後，確認後端全部測試與 CI 全綠即可
