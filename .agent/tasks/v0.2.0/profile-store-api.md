# 派工單 profile-store-api

- **類型**：feat
- **負責**：backend
- **目標**：實作 DESIGN-03 批 4（BE-07）：V7、客戶資料庫與搜尋（`profiles.md`）、旅程列表與唯讀旅程頁 API（`journeys.md` §2）、`/enrollments` 瀏覽
- **背景**：前端（journey-customer-ui，PR #98）已對這些契約 mock 完成；user-auth-api（PR #104）已合併，角色矩陣依 `auth.md` §2。D-048 搜尋完全比對、D-050 列表不顯示人數
- **設計依據**：`docs/design/DESIGN-03.md` 做法 #4、#5、#6 與派工順序批 4；`docs/contracts/profiles.md`、`docs/contracts/journeys.md` §2、`docs/contracts/auth.md` §2；`docs/spec/07-database-schema.md` §12
- **相依**：無（BE-06 已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/profile/`、`api/src/main/java/com/cdp/journey/`、`api/src/main/java/com/cdp/query/`
  - `api/src/main/java/com/cdp/ingest/EventIngestionService.java`、`api/src/main/java/com/cdp/persistence/JdbcProfileStore.java`
  - `api/src/main/resources/db/migration/V7__profile_store.sql`
  - `api/src/test/java/com/cdp/` 中對應上述類別的單元測試（`acceptance/` 除外）
- **禁區**：`auth.md` 與 security／user 套件（另有 auth-session-design 進行中）；不可改驗收測試；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/profile-store-api/`
- **驗收條件**：
  - [ ] `profiles.md` 全部 API：四種方式搜到客戶（完全比對）、客戶頁資料、遮罩依白名單
  - [ ] `journeys.md` §2 旅程列表與唯讀旅程頁（含版本切換）、`/enrollments` 瀏覽與 V7 兩個索引
  - [ ] 事件攝取對未知客戶建空白 profile（`on conflict do nothing`），寫入只經 `ProfileWriter`
  - [ ] 三種角色依 `auth.md` §2 放行／拒絕
  - [ ] 既有驗收全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/profile-store-api.json`

## 執行資訊（task.py 產生）

- Issue：#106
- 分支：`feat/profile-store-api`（從 develop 開出）
- 工作目錄：`.worktrees\profile-store-api`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-23

## 範圍補充（qa 寫驗收時發現，manager 追認）

- 負責檔案加：`api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`、`.env.example`（只為接上 `CDP_PHONE_DEFAULT_COUNTRY_CODE` 等本單需要的設定）
- 驗收測試在 `acceptance/profile-store-api/`（套件 `com.cdp.acceptance.profilestoreapi`，commit b210339，已 lock）
- qa 的假設（不符就在回報寫 disputes，不要改測試）：
  1. 電話預設國碼屬性名採 `cdp.phone.default-country-code`、`cdp.phone-default-country-code`、`cdp.profile.phone-default-country-code`、`cdp.profile.default-country-code` 其中一個
  2. 寫 `profile`／`profile_identifier` 的 SQL 只出現在 `com.cdp.profile.ProfileWriter`；`EventIngestionService` 與 `com.cdp.profile.ProfileService` 都依賴它（ArchUnit＋原始碼掃描）
  3. 各列表 `cursor` 不合法 → 400 `VALIDATION_FAILED`；攝取建空白客戶為同步（202 後立即可 GET）；被拒絕的事件不建客戶
  4. 布林屬性遮罩為 `"***"`（journey-engine §4.4）

## 追加 2026-10-23

## 範圍修正（manager 追認 worker 回報的 open_questions，取代上方「負責檔案」）

- **負責檔案**：
  - `api/src/main/java/com/cdp/profile/`、`api/src/main/java/com/cdp/journey/`、`api/src/main/java/com/cdp/query/`
  - `api/src/main/java/com/cdp/ingest/EventIngestionService.java`、`api/src/main/java/com/cdp/persistence/JdbcProfileStore.java`
  - `api/src/main/java/com/cdp/persistence/JdbcEnrollmentQueryStore.java`、`api/src/main/java/com/cdp/persistence/JdbcJourneyQueryStore.java`（新增）
  - `api/src/main/java/com/cdp/common/web/ErrorCode.java`（只新增 `profiles.md` 的錯誤碼）
  - `api/src/main/resources/db/migration/V7__profile_store.sql`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`、`.env.example`
  - `api/src/test/java/com/cdp/` 中對應上述類別的單元測試（`acceptance/` 除外）
- 舊驗收 `acceptance/query/EnrollmentQueryAcceptanceTest` 的爭議交 architect 仲裁中，結果另行通知；`updated_at` 行為一併請 architect 確認
