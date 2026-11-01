PASS d783ff7

# QA 重驗 profile-search-api（PR #111，commit d783ff7）

## 驗證項目

| 項目 | 結果 |
|---|---|
| review 退回項 #1（EMAIL 全形空白變 500） | `IdentifierNormalizer.java` EMAIL 改為 `Pattern.compile("^[^@\\s]+@[^@\\s]+$", Pattern.UNICODE_CHARACTER_CLASS)`；`IdentifierNormalizerTest` 新增 `"a　b@example.com"`（U+3000）與另一個空白字元案例，皆 `isEmpty()`，測試 4 個全綠。`ProfileWriter.java:195-198` 在 normalize 為空時加入 FieldError，回 400 VALIDATION_FAILED，不再進入 claim 的 insert，所以不會觸發 DB CHECK。Java 的 Unicode White_Space 範圍包含 musl `iswspace` 所認的空白，DB 能擋的 API 都先擋 |
| 後端全部測試（含驗收，Docker） | `docker compose --env-file .env -f .worktrees/profile-search-api/compose.yaml run --rm test`：`Tests run: 377, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（exit 0）。含 `com.cdp.acceptance.profile-search-api` 全部、`EnrollmentQueryAcceptanceTest`、`ArchitectureTest`（10）、`IdentifierNormalizerTest`（4） |
| lint | `./mvnw -q -o spotless:check` exit 0 |
| 本輪修改範圍 | `git diff 9c9520e HEAD -- api/` 只動 `IdentifierNormalizer.java`（1 行）與 `IdentifierNormalizerTest.java`（+2 行），皆在負責檔案內；未動驗收測試；無寫死設定值 |
| 驗收條件五項 | 由上述 377 個測試涵蓋，皆通過；本輪沒有改動它們涉及的程式 |

## 備註（非阻擋）

- 沒有 HTTP 層的回歸測試重現 `PUT /profiles/{id}` 帶全形空白 EMAIL 回 400；目前只有 normalizer 單元測試加上程式路徑檢查。建議下一次有機會時補一個驗收案例。
- review 的建議保險層（claim 接住 `DataIntegrityViolationException` 轉 400）與兩項非阻擋備註未做，review 標明為建議／非阻擋。
- CI 狀態我沒有檢查；依派工說明，若 Check 只在 `LoginSessionAcceptanceTest.role_change_takes_effect_on_next_request` 失敗，屬 develop 既有的測試順序問題（`user-session-revoke.ruling.md`），Security job 的 osv-scanner 失敗由 deps-security-upgrade 處理，皆不算本單問題。本機容器測試 377 個全綠，該測試也通過。
