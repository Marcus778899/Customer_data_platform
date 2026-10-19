# 派工單 FIX-01

- **類型**：fix
- **負責**：backend
- **目標**：Spring MVC 內建例外（415 等）也回契約 §5 的 problem+json 格式
- **背景**：BE-04 驗證時發現：POST /api/v1/auth/login 用 text/plain 回的是 Spring 預設 ProblemDetail，缺 type、code、request_id（BE-04 qa FAIL 的根因，PR #12 相關）。
  錯誤處理在 BE-01 的 `com.cdp.common.web.ApiExceptionHandler`；不只 415，Spring 框架其他內建例外（JSON 解析失敗、405、找不到路由等）也要依 §5 輸出，並帶 request_id。
- **設計依據**：docs/contracts/journey-engine.md §1.2、§5；docs/design/DESIGN-01.md
- **相依**：無（BE-01 已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/common/web/`
  - `api/src/test/java/com/cdp/common/`
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、`com.cdp.security` 及其他套件、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/common/`
- **驗收條件**：
  - [ ] 不支援的 Content-Type 回 415，problem+json，code `UNSUPPORTED_MEDIA_TYPE`，含 type、title、status、request_id
  - [ ] JSON 解析失敗回 400 `VALIDATION_FAILED`；其他 Spring MVC 內建例外也依 §5 格式回應，皆帶 request_id，不外洩例外訊息或堆疊
  - [ ] 既有測試（含 BE-01 驗收測試）與 ArchitectureTest 仍全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-01.json`

## 執行資訊（task.py 產生）

- Issue：#15
- 分支：`fix/fix-01`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-01`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- qa B 判定 PASS（d5f5a73）。預先合 feat/be-04 試跑：BE-04 驗收全過，但本單的 FrameworkErrorFormatAcceptanceTest 有 5 個變 401（測試用 `Bearer qa-key`＋mock user，BE-04 的金鑰過濾器不接受）。
- 處理：qa 在本分支調整驗收測試的認證，改用 `application-test.yml` 的測試金鑰（同 JourneyEngineAcceptanceTest 的做法），須在「本分支單獨」與「合 feat/be-04 後」兩種情況都通過。worker 不需動。
