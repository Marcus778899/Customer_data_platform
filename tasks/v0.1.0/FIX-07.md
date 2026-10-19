# 派工單 FIX-07

- **類型**：fix
- **負責**：backend
- **目標**：查詢參數 `q` 含 NUL 字元（U+0000）時回 `400 VALIDATION_FAILED`，不回 500
- **背景**：UAT v0.1.0 F-2（D-028 納入）：`GET /api/v1/enrollments?q=x%00y` → PostgreSQL `invalid byte sequence for encoding "UTF8": 0x00` → 500。詳情路徑 `/enrollments/x%00y` 已正確回 400，兩者要一致。見 docs/uat/v0.1.0.md F-2。
- **設計依據**：docs/contracts/journey-engine.md §4、§5
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/query/`
  - `api/src/test/java/com/cdp/query/`
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、其他套件、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/query/`
- **驗收條件**：
  - [ ] `q` 含 NUL → `400 VALIDATION_FAILED`，`errors` 指出欄位 `q`，含 `request_id`
  - [ ] `status`、`cursor` 含 NUL 也不回 500
  - [ ] 一般 `q`（含其他特殊字元、長字串）行為不變
  - [ ] 既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-07.json`

## 執行資訊（task.py 產生）

- Issue：#60
- 分支：`fix/fix-07`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-07`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（PR #65 architect review changes）：
  1. `EnrollmentQueryService.java` 的 `rejectNul` 原始碼內含真正的 0x00 位元組，git 把檔案視為 binary。改用 `(char) 0`（同 `com.cdp.ingest.EventValidator`）；完成後 `git diff develop...HEAD` 必須顯示文字差異，不得出現 "Binary files differ"。原始碼不得含任何控制字元。
  2. cursor 解碼後含 NUL 仍回 500：合法 base64url 解出的內容含 NUL（例 `cursor=MjAyNi0wMS0wMVQwMDowMDowMFp8YQBi`，解碼為 `2026-01-01T00:00:00Z|a\0b`）會傳進 SQL。列表與 `/enrollments/{id}/deliveries` 都要回 `400 VALIDATION_FAILED`、field `cursor`。做法建議：在 `PageCursor.decode` 檢查解碼後內容；原始字串上的 NUL 檢查可移除。`PageCursorTest` 補單元測試。
- 驗收條件追加：
  - [ ] 解碼後含 NUL 的 cursor（列表、deliveries）→ 400 VALIDATION_FAILED，errors 欄位 `cursor`
  - [ ] `git diff develop...HEAD` 對 `EnrollmentQueryService.java` 顯示文字差異
- qa 先補驗收測試（列表與 deliveries 各一筆上述 cursor，斷言 400、code、field cursor）。
