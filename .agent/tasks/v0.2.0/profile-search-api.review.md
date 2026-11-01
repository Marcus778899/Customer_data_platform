APPROVE d783ff7

# Review profile-search-api 第 2 輪（PR #111，commit d783ff7）

只看上一輪（9c9520e）之後的變更；其餘部分上一輪已經審過，結果沒變。

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 變更範圍 | `git diff 9c9520e d783ff7 -- api/` 只動兩個檔：`IdentifierNormalizer.java`（1 行）、`IdentifierNormalizerTest.java`（+2 行），都在負責檔案內。其他變更來自 merge commit d090bc3（develop 的流程檔遷移），不是 worker 改的。驗收測試沒動 |
| 退回項 #1 | `IdentifierNormalizer.java:12` 的 EMAIL 正則已經加上 `Pattern.UNICODE_CHARACTER_CLASS`。測試裡新增的兩個字元，用位元組確認過是 U+3000（全形空白）和 U+2003，正好是上一輪在 DB 實測會讓 CHECK 失敗的兩個。`ProfileWriter.normalizeIdentifiers` 在 normalize 結果為空時會加 FieldError，回 400 `VALIDATION_FAILED`，不會走到 `claim` 的 insert。搜尋也用同一個 normalizer（`ProfileSearchService.java:90`），行為一致 |
| 和 DB CHECK 是否一致（V7:21-26） | EMAIL：Java 的 Unicode White_Space 範圍包含 musl `iswspace` 認得的字元，`strip()` 之後的值不含空白，所以 `btrim` 不會改變它。PHONE／其他類型和上一輪相同，沒問題 |
| CI | Check 只有 `LoginSessionAcceptanceTest.role_change_takes_effect_on_next_request` 1 項失敗（run 41093292457，是 develop 既有問題，見 `user-session-revoke.ruling.md`），`profilesearchapi.*` 的 9 個測試類別、`EnrollmentQueryAcceptanceTest` 全部通過。Security 失敗是 osv-scanner 掃到的相依漏洞（由 deps-security-upgrade 處理）。因為 Check 在測試階段就失敗，這次 CI 沒跑到 coverage gate，合併前仍要等 CI 全綠 |

## qa 備註的兩項：判定不需要，本單不阻擋

| 項目 | 判斷 |
|---|---|
| HTTP 層的回歸驗收案例 | 不需要。問題出在 normalizer 的正則，它和 HTTP 之間的路徑（空結果、FieldError、400）是既有程式，已經有 `ProfileWriteAcceptanceTest` 的格式錯誤案例覆蓋。單元測試已經鎖住這次修的字元。補驗收案例要 qa 重寫並重新 lock，換來的保障有限 |
| 保險層（`claim` 接住 `DataIntegrityViolationException`） | 不阻擋，建議排進 backlog。原因：還有一個理論上的不一致沒處理。DB 用 `id_value = lower(...)`（musl `towlower`），Java 用 `toLowerCase(Locale.ROOT)`，兩者的 Unicode 版本不同。如果有某個字元 musl 會轉小寫、Java 不轉，請求還是會變成 500。目前沒有實例，也不是這次退回的項目。如果要做，注意 `claim` 的 insert 也可能因為外鍵等其他約束失敗，轉 400 時要只看 `ck_profile_identifier_norm` 這個約束 |

## 非阻擋備註

上一輪的兩項（`ProfileWriter.java:87` 的 `\u0000` 字面誤判、事件 `identity.profile_id` 沒有驗證 ULID）都還沒處理，建議和上面的保險層一起排進 backlog。
