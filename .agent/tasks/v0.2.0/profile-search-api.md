# 派工單 profile-search-api

- **類型**：feat
- **負責**：backend
- **目標**：實作 DESIGN-03 批 4（BE-07）：V7、客戶資料庫與搜尋（`profiles.md`）、旅程列表與唯讀旅程頁 API（`journeys.md` §2）、`/enrollments` 瀏覽（q 選填）
- **背景**：取代作廢的 profile-store-api（D-072）。前次仲裁 `reports/profile-store-api.ruling.md`：舊驗收 `EnrollmentQueryAcceptanceTest` 不帶 q 判 400 是測試錯，qa 須刪該項，故列入本單驗收範圍。architect 已同意：`PUT /profiles` 只在內容變動才推進 `updated_at`；EMAIL CHECK 用 `^[^@\s]+@[^@\s]+$`。電話預設國碼屬性名用 `cdp.phone.default-country-code`（`CDP_PHONE_DEFAULT_COUNTRY_CODE`）
- **設計依據**：`docs/design/DESIGN-03.md` 做法 #4～#6、派工順序批 4；`docs/contracts/profiles.md`、`journeys.md` §2、`journey-engine.md` §4、`auth.md` §2；`docs/spec/07-database-schema.md` §12
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/profile/`、`api/src/main/java/com/cdp/journey/`、`api/src/main/java/com/cdp/query/`
  - `api/src/main/java/com/cdp/ingest/EventIngestionService.java`
  - `api/src/main/java/com/cdp/persistence/JdbcProfileStore.java`、`JdbcEnrollmentQueryStore.java`、`JdbcJourneyQueryStore.java`（新增）
  - `api/src/main/java/com/cdp/common/web/ErrorCode.java`（只新增 `profiles.md` 的錯誤碼）
  - `api/src/main/resources/db/migration/V7__profile_store.sql`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`、`.env.example`
  - `api/src/test/java/com/cdp/profile/`、`api/src/test/java/com/cdp/journey/`、`api/src/test/java/com/cdp/query/`、`api/src/test/java/com/cdp/persistence/`、`api/src/test/java/com/cdp/ingest/`
- **禁區**：security／user 套件與 `auth.md`；不可改驗收測試；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/profile-search-api/`
  - `api/src/test/java/com/cdp/acceptance/query/EnrollmentQueryAcceptanceTest.java`
- **驗收條件**：
  - [ ] `profiles.md` 全部 API：四種方式搜到客戶（完全比對）、客戶頁、遮罩白名單、409 衝突整筆回滾
  - [ ] `journeys.md` §2 列表與唯讀旅程頁（含版本切換、無人數）、`/enrollments` 不帶 q 瀏覽、V7 索引與回填
  - [ ] 事件攝取對未知客戶同步建空白 profile；寫 `profile`／`profile_identifier` 的 SQL 只在 `com.cdp.profile.ProfileWriter`
  - [ ] 三種角色依 `auth.md` §2 放行／拒絕
  - [ ] 既有驗收全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/profile-search-api.json`

## 執行資訊（task.py 產生）

- Issue：#108
- 分支：`feat/profile-search-api`（從 develop 開出）
- 工作目錄：`.worktrees\profile-search-api`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-24

## 範圍修正（manager 筆誤：兩個 persistence 檔原本只寫檔名，取代上方「負責檔案」）

- **負責檔案**：
  - `api/src/main/java/com/cdp/profile/`、`api/src/main/java/com/cdp/journey/`、`api/src/main/java/com/cdp/query/`
  - `api/src/main/java/com/cdp/ingest/EventIngestionService.java`
  - `api/src/main/java/com/cdp/persistence/JdbcProfileStore.java`
  - `api/src/main/java/com/cdp/persistence/JdbcEnrollmentQueryStore.java`
  - `api/src/main/java/com/cdp/persistence/JdbcJourneyQueryStore.java`
  - `api/src/main/java/com/cdp/common/web/ErrorCode.java`（只新增 `profiles.md` 的錯誤碼）
  - `api/src/main/resources/db/migration/V7__profile_store.sql`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`、`.env.example`
  - `api/src/test/java/com/cdp/profile/`、`api/src/test/java/com/cdp/journey/`、`api/src/test/java/com/cdp/query/`、`api/src/test/java/com/cdp/persistence/`、`api/src/test/java/com/cdp/ingest/`
- architect 確認：`engine.ProfileStore.getFields` 不屬本單（延到 v0.3.0 BE-09）；`ProfileReadStore`、`profile_identifier.profile_id` 外鍵可接受。程式不用改

## 追加 2026-10-30

### .agent/reports/profile-search-api.review.md

REQUEST_CHANGES 9c9520e

# Review profile-search-api（PR #111，commit 9c9520e）

## 要修正

| # | 檔案:行號 | 問題 | 要怎麼改 |
|---|---|---|---|
| 1 | `api/src/main/java/com/cdp/profile/IdentifierNormalizer.java:12`（對照 `V7__profile_store.sql:23`、`ProfileWriter.java:122` `claim`） | EMAIL 正規化的 Java 正則 `\s` 只認 ASCII 空白，DB 的 `ck_profile_identifier_norm` 用 `[:space:]`，在 `postgres:18-alpine`（en_US.utf8）下也認 Unicode 空白。實測：`('a'\|\|chr(8195)\|\|'b@x.com') ~ '^[^@[:space:]]+@[^@[:space:]]+$'` 與 `chr(12288)`（全形空白）都回 `f`，ASCII 的 `ab@x.com` 回 `t`。所以 `PUT /profiles/{id}` 帶 `{"type":"EMAIL","value":"a　b@example.com"}` 時，Java 驗證會放行，接著 `claim` 的 insert 觸發 CHECK，丟出 `DataIntegrityViolationException`，被 `ApiExceptionHandler` 的兜底接走，最後回 500。契約 profiles.md §1.1 寫的是不合法要回 `400`（後端規範 §1：5xx 只能用在伺服器自己的錯）。全形空白是中文輸入時常見的值。另外 DESIGN-03 做法 #5 要求 API 擋得下的值 DB 也擋，現在是反過來：DB 擋了但 API 沒擋 | `EMAIL` 改成 `Pattern.compile("^[^@\\s]+@[^@\\s]+$", Pattern.UNICODE_CHARACTER_CLASS)`。Java 的 Unicode White_Space 涵蓋的範圍比 musl 的 `iswspace` 大，DB 能擋的 API 都會先擋。`IdentifierNormalizerTest` 補一個案例：`"a　b@example.com"`、`"a b@example.com"` 要是 `isEmpty()`。建議再加一層保險：`claim` 的 insert 接住 `DataIntegrityViolationException`，轉成 `400` `VALIDATION_FAILED`（field `identifiers[i].value`），不要讓它變成 500 |

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 負責檔案（以「範圍修正」為準） | 37 個檔案都在範圍內；worker 的 commit 9c9520e 沒有動驗收測試。驗收測試的變更只在 qa 的 54bc80b：`acceptance/profile-search-api/`，以及依仲裁刪掉的 `EnrollmentQueryAcceptanceTest` 一行；security／user／auth.md 沒動 |
| profiles.md §1.1 正規化 | PHONE、其他類型和契約一致，DB CHECK 也對得上：`btrim` 只去空格，Java `strip()` 處理後一定通過；`char_length` 和 code point 計數相同。EMAIL 見問題 1 |
| profiles.md §2 寫入 | 單一交易；先 `insert … on conflict do nothing` 再 `for update` 鎖列；識別碼依 (type,value) 排序後再取鎖；`claim` 遇到衝突回 409，訊息不揭露對方 ID；`ApiException` 是 runtime 例外，整筆會回滾；屬性淺層合併、值為 null 就刪、鍵用正則驗、32 KB 在應用層和 DB 都擋（DB 依 jsonb 文字長度，update 時接住轉 400）；內容沒變不推進 `updated_at`（已同意） |
| profiles.md §3 查詢 | 四種比對都是精確比對、取聯集、同一客戶只列一次；`MatchedBy` 宣告順序讓 PROFILE_ID 排最前，再依 `updated_at` 新到舊；最多 20 筆；q 為空白、超過 200 字或含 NUL 回 400；客戶不存在回 404 `PROFILE_NOT_FOUND`；`/{id}/enrollments` 先確認客戶存在再查 |
| 遮罩 | 沿用 `PiiMasker`：`attributes` 根路徑；`identifiers[i].value` 以 type 小寫查白名單；`masked_paths` 有帶出來 |
| journeys.md §2 | 「取最新列」推導正確；`updated_at` = 最新列的 `created_at`；`has_draft=false`、`review_status=null`；versions 新到舊；cursor 用 (created_at, journey_id)；q 名稱包含、不分大小寫，LIKE 有跳脫；status 有驗證；旅程或版本不存在回 404；沒有人數欄位 |
| `/enrollments` | 省略 q 就是瀏覽；有帶 q 但為空白回 400（仲裁的契約問題 1，現行可接受）；新增 `journey_id` 篩選；SQL 都有參數化 |
| 事件攝取 | `ensureExists` 在驗證之後、事件落地之前；冪等（`on conflict do nothing`）；批次也是逐筆走 `ingest` |
| V7 | 只新增 table 和 index（expand），回填有冪等；和 V1–V6 一樣沒有 down 檔（專案用 Flyway，維持慣例） |
| 角色 | `SecurityConfig` 已有 profiles／journeys 規則（BE-06），`ProfileStoreRolesAcceptanceTest` 全綠 |
| 設定 | `CDP_PHONE_DEFAULT_COUNTRY_CODE` 走 yml 和 `.env.example`（已有 `CHANGE_ME`），啟動時驗證，沒有寫死的值 |
| 測試 | 驗收測試對應全部驗收條件，qa 回報 377 個全綠；單元測試只有 `IdentifierNormalizerTest`，其餘靠驗收測試（含容器）覆蓋，CI 的 coverage gate 會再把關 |

## 非阻擋備註

- `ProfileWriter.java:87`：`mergedJson.contains("\\u0000")` 會把屬性值裡的字面字串 `\u0000`（反斜線加 u0000）誤判成 NUL 並回 400。可以改成遞迴檢查字串值裡有沒有 `'\0'`。
- 事件的 `identity.profile_id` 沒有驗證是不是 ULID（v0.1.0 起就是這樣），`ensureExists` 可能建出非 ULID 的客戶，這類客戶無法用 `PUT` 寫入。之後改契約時再一起處理（journey-engine §3.2 和 profiles.md §2 都寫 ULID）。
