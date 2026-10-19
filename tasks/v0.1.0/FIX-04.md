# 派工單 FIX-04

- **類型**：fix
- **負責**：backend
- **目標**：認證強化：授權預設拒絕、機密不經 toString 外洩、啟動時驗證金鑰設定
- **背景**：項目見 docs/design/DESIGN-01.md「後續實作項」認證強化列（來源 PR #14 review、D-025）。
  不改契約 §1.1 的角色規則，只把「GET 任一路徑只需 authenticated」改成明列契約端點、其餘拒絕。
- **設計依據**：docs/contracts/journey-engine.md §1.1、§1.2、§5；docs/design/DESIGN-01.md 做法 #10
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/security/`
  - `api/src/test/java/com/cdp/security/`
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、其他套件、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/security/`
- **驗收條件**：
  - [ ] 授權為預設拒絕：只有契約 §1.1 列出的端點可存取；未列出的路徑（含 GET）對 INGEST 與客服 session 回 403、對未認證回 401；FORWARD 不再一律放行
  - [ ] `AuthProperties`、`LoginRequest` 的 `toString` 不含金鑰與密碼
  - [ ] logout 清 cookie 的名稱與路徑取自 `server.servlet.session.cookie.*`，不寫死
  - [ ] 啟動時金鑰或客服密碼為空、或兩把金鑰相同 → 啟動失敗並給出不含機密值的訊息
  - [ ] 客服 session 閒置逾時後失效（qa 驗收）；既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-04.json`

## 執行資訊（task.py 產生）

- Issue：#29
- 分支：`fix/fix-04`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-04`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1：ADMIN 金鑰對未列路徑維持現行行為（通過授權、落到 MVC 回 404），既有 `acceptance/common` 的兩個 404 測試不改；預設拒絕只針對 INGEST 金鑰與客服 session。驗收測試在 commit 3727dcf。

## 追加 2026-10-18

- 追加 2（worker 回報驗收測試兩處問題，非實作缺陷，不計 qa FAIL）：
  1. `acceptance/common` 的 405 測試以 INGEST 金鑰 GET /api/v1/events，與預設拒絕（INGEST 對未列方法 403）矛盾 → qa 改用 ADMIN 金鑰（ADMIN 通過授權，落到 MVC 回 405），DefaultDenyAcceptanceTest 維持 403。
  2. `StartupValidationAcceptanceTest` 用 `SpringApplicationBuilder.properties()` 傳覆寫值（優先序最低，被 application-test.yml 蓋掉）→ qa 改用命令列參數 `run(args)` 等高優先序方式。
- worker 實作 commit 7dfa3e0 不需改；qa 修完後重跑 `make check`。

## 追加 2026-10-18

- 追加 3（qa FAIL 1）：`SecurityConfig` 的 `GET /api/v1/enrollments/**` 過寬，改為只放行契約 §4 的三個樣式（`/enrollments`、`/enrollments/{id}`、`/enrollments/{id}/deliveries`）。qa 先補回歸測試：SUPPORT 與 INGEST 呼叫 `GET /api/v1/enrollments/x/foo` 回 403。
- 一併處理：啟動日誌出現 Spring 預設 `Using generated security password: …`，請在 `com.cdp.security` 內排除（例如宣告不產生密碼的 UserDetailsService），日誌不得再出現該行。

## 追加 2026-10-18

- 追加 4（review changes）：`GET /api/v1/auth/me` 只開給 SUPPORT（契約 §1.2），改 `.hasRole(support)`；INGEST、ADMIN 金鑰呼叫回 403 FORBIDDEN。qa 先補回歸測試；同時把 `StartupValidationAcceptanceTest` 的訊息斷言收窄為 cause 鏈含 `IllegalStateException` 且訊息含 `cdp.security.` 屬性名。
