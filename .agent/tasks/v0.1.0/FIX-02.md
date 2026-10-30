# 派工單 FIX-02

- **類型**：fix
- **負責**：backend
- **目標**：錯誤處理收斂：405／406 專用 code 與標頭、移除 @Order 依賴、Security 例外不轉 500
- **背景**：項目清單見 docs/design/DESIGN-01.md「後續實作項」的「錯誤處理」列（來源 PR #16 review、D-025）。契約 §5 已有 code 表（DESIGN-02）。
  本單隨單授權改 `application.yml`（只限 `spring.mvc.problemdetails.enabled` 與 datasource `logServerErrorDetail=false`）與 `T/architecture/`。
- **設計依據**：docs/contracts/journey-engine.md §5；docs/design/DESIGN-01.md 做法 #11、「後續實作項」
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/common/web/`
  - `api/src/main/resources/application.yml`
  - `api/src/test/java/com/cdp/common/`
  - `api/src/test/java/com/cdp/architecture/`
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`api/pom.xml`、`api/src/test/resources/`、`com.cdp.security` 與其他套件、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/common/`
- **驗收條件**：
  - [ ] 405 回 `METHOD_NOT_ALLOWED` 並帶 `Allow` 標頭；406 回 `NOT_ACCEPTABLE` 且仍為 problem+json；415 帶 `Accept` 標頭；400 以外的 4xx 不帶 `errors`
  - [ ] 關閉 `spring.mvc.problemdetails.enabled`，`ApiExceptionHandler` 不再依賴 `@Order` 仍能接到框架例外
  - [ ] controller 內拋出的 `AccessDeniedException`／`AuthenticationException` 回 403／401（交回 Security），不轉 500
  - [ ] datasource 加 `logServerErrorDetail=false`；ArchitectureTest 新增「唯一 `@RestControllerAdvice`」與「禁用 `ResponseStatusException`」規則並通過
  - [ ] `common/web` 補單元測試；既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-02.json`

## 執行資訊（task.py 產生）

- Issue：#25
- 分支：`fix/fix-02`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-02`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
