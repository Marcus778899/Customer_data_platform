# 設計：客製資料整合以 PostgreSQL 與核心解耦（DESIGN-04，只出方案）

- 依據：D-037、D-051、D-052；spec 01 §3～§7、§9；spec 07 §2、§6、§11；DESIGN-03 `profiles.md` §1～§2、spec 07 §12（`profile`、`profile_identifier`）
- 現況：唯一入口 `POST /v1/events` → `EventValidator.validate` → `EventIngestionService.ingest` → `EventStore.insertIfAbsent` → `EventDispatcher` 輪詢扇出

## 摘要（給甲方）

建議在資料庫裡另開一個「收件區」（`landing`）。客戶的 ETL（Airflow、dbt）只能把資料「放進」收件區，碰不到平台自己的資料表；平台定時去收件區取件，用和事件 API 完全相同的規則檢查、去重、歸戶，再寫進正式資料，並把每一列的處理結果（成功、重複、錯在哪個欄位）寫回同一列，讓 ETL 自己查得到。好處：髒資料擋在門外、ETL 重跑不會重複、平台改內部結構不影響客戶、匯入歷史資料不會誤發訊息。代價：資料不是「寫入即生效」，會有幾秒的取件延遲。

## §1 方案比較

| 面向 | A 收件區＋核心輪詢吸收（建議） | B ETL 直接寫核心表（`profile`、`event`） | C ETL 一律走 HTTP API | D 核心去讀客戶自己的表／view |
|---|---|---|---|---|
| 驗證與髒資料隔離 | 核心逐列套 `EventValidator`／`profiles.md` §1.1；壞列標 `REJECTED` 留在收件區，不進核心 | 只剩 DB 約束；格式、正規化、遲到規則全被繞過，壞資料直接污染 | 同 A（同一套驗證） | 同 A，但對方表結構不受我方控制，欄位一改就壞 |
| 去重與冪等 | 事件以 `(source_id, external_key)` 推導 `event_id`，沿用 `event` 主鍵；重跑同批 = `DUPLICATE` | ETL 須自己產 ULID 並保證穩定，做不到就重複 | 同 A，但 ETL 須自產穩定 ULID | 須記「讀到哪」水位；對方更新舊列時難判斷 |
| 歸戶時機 | 吸收時依識別碼查 `profile_identifier`，查不到才建；衝突標 `CONFLICT` | ETL 須先知道平台的 `profile_id`，實務上不知道 | 同 A（在 API 內） | 同 A |
| 觸發旅程 | 寫進 `event` 後由 `EventDispatcher` 照常派發；可標 `backfill` 不建 Enrollment | 繞過 `dispatched_at` 欄位規則，易漏派或重派 | 照常 | 照常 |
| 錯誤回報給 ETL | 結果寫回同列（狀態＋逐欄錯誤），ETL 用 SQL 查；可做 Airflow sensor | 只有 SQL 例外，整筆交易失敗 | HTTP 回應（spec 01 §9 逐筆結果）；ETL 要寫程式處理 | 我方日誌，對方看不到 |
| 大量回填 | `COPY` 進收件區很快；核心分批吸收，量大時延遲變長但不擋其他迴圈 | 快，但無驗證 | 慢：每批 500 筆、要寫 HTTP 客戶端 | 快，但要長時間持有對方連線 |
| 權限與 schema 演進 | ETL 帳號只有收件區欄位級 INSERT；核心表可自由改；收件區只做「加欄位」 | ETL 須有核心表寫權；核心每次改表都破壞客戶 ETL | 最乾淨；API 版本化 | 我方要讀對方資料庫，權限與網路由客戶掌控 |
| dbt／Airflow 友善度 | 高（寫表是它們的本行）；dbt 寫入非自己擁有的表需調查 2 | 高 | 低（dbt 不會打 API） | 高，但部署耦合 |

結論：A。B 違反「核心不讓外部直寫」的基本隔離；C 留給即時來源（webhook、SDK）；D 只在客戶不肯改 ETL 時當轉接（之後另議）。

## §2 建議做法（方案 A）

| # | 項目 | 設計 |
|---|---|---|
| 1 | 介面 = 兩張表 | `landing.profile_upsert`、`landing.event_import`；獨立 schema，由核心 Flyway 管理；核心表不出現在 ETL 視野 |
| 2 | 欄位類別 | **控制**：`seq` BIGINT identity（處理順序）、`batch_id`（ETL 一次執行的代號，必填）、`source_id`（`DEFAULT current_user`，ETL 不可寫，同 spec 01「租戶由憑證推導」原則）、`submitted_at` DEFAULT now()。**內容**（ETL 寫）：事件 = `external_key`、`name`、`occurred_at`、`identifiers` JSONB、`properties` JSONB、`backfill` BOOL；客戶 = `identifiers` JSONB、`attributes` JSONB（語意同 `profiles.md` §2 淺層合併，`null` 刪鍵）。**狀態**（只有核心寫）：`status`、`errors` JSONB（同 `FieldError` 格式）、`profile_id`、`event_id`、`attempts`、`processed_at` |
| 3 | 狀態值 | `PENDING` → `APPLIED`／`DUPLICATE`／`REJECTED`（驗證失敗）／`CONFLICT`（識別碼分屬不同客戶，不合併）；事件找不到客戶 → 留 `PENDING`、`attempts + 1`，達上限轉 `REJECTED`（呼應 spec 01 §5 `EXTERNAL_SYSTEM` 待處理佇列） |
| 4 | 誰能寫哪裡 | ETL 登入（每個來源一個帳號，名稱 = `source_id`）：收件區「內容欄」欄位級 INSERT＋SELECT，無 UPDATE／DELETE，核心 schema 無權限。核心池：收件區 SELECT、UPDATE 狀態欄、DELETE 過期列。DDL 只有 Flyway 帳號 |
| 5 | 吸收（D-037 輪詢） | 新迴圈 `LandingImporter`：每 tick 先 `profile_upsert` 後 `event_import`，`WHERE status='PENDING' ORDER BY seq LIMIT n FOR UPDATE SKIP LOCKED`（partial index `WHERE status='PENDING'`）；每列一個交易：核心寫入與狀態回寫同交易；滿批續做（同 DESIGN-03 `CDP_LOOP_MAX_DRAIN`）；之後需要再加 LISTEN/NOTIFY |
| 6 | 與事件 API 的關係 | 同一條路：收件列轉成 `EventRequest`（`source_type = EXTERNAL_SYSTEM`）→ `EventValidator.validate` → `EventStore.insertIfAbsent` → `EventDispatcher`；客戶列 → `ProfileService` 同一個寫入方法。不另寫一套規則 |
| 7 | `event_id` | `external_key` 必填（ETL 的自然鍵，例 `order_id`）；核心以 SHA-256(`source_id`, `external_key`) 取 128 位元編成 ULID 格式；`occurred_at` 改了重跑仍判為重複（spec 01 §7「穩定 id」） |
| 8 | 回填 | `backfill = true` → 寫入但不建 Enrollment（仍可喚醒既有等待，同 spec 01 §6 遲到規則）；未標但遲到 > 24h 已由現有 `LATE` 處理 |
| 9 | 回報與清理 | view `landing.batch_summary`（每個 `batch_id` 各狀態筆數）給 ETL 判斷成敗；`APPLIED`／`DUPLICATE` 列超過保留期由核心刪除，`REJECTED`／`CONFLICT` 保留到 ETL 重送 |
| 10 | schema 演進 | 收件區只加可為 null 的欄位；要破壞相容時開新表（`event_import_v2`）並行一段期間；客戶自訂屬性都在 JSONB，不需改表 |

## §3 對 DESIGN-03（v0.2.0）

| 現在就預留 | 可以延後 |
|---|---|
| `ProfileService` 的寫入邏輯放在 service、以「請求物件 → 結果（含 `FieldError` 清單與衝突）」回傳，不只丟 HTTP 例外；Controller 只做轉換 | 收件區表、ETL 帳號、`LandingImporter`、`batch_summary` |
| 攝取時建空白客戶放在 `EventIngestionService`（DESIGN-03 做法 #5 已是），不放 Controller | 依識別碼查／建客戶（spec 01 §5 `ProfileStore.resolve`）；v0.2.0 仍要求 `profile_id` |
| `profile_identifier` 主鍵維持「一個識別碼只屬一位客戶」（已是），收件區衝突判定靠它 | `backfill` 對應的 `dispatch_outcome` 新值（要改 V4 的 CHECK） |
| 核心表與 schema 不對 `PUBLIC` 授權；新增 DB 角色一律在 Flyway `afterMigrate` 管（DESIGN-03 做法 #1 同處） | 保留期清理、LISTEN/NOTIFY、ETL 撰寫指南與 dbt 範例 |

## §4 版本與單數

- 建議：DESIGN-03 第一版之後的版本（第一版要先有 `profile` 表與單一租戶定案）；約 6 張：設計細化（契約 `landing.md`＋spec 07）1、BE 收件區＋客戶吸收＋歸戶 1、BE 事件吸收＋回填 1、qa 2、整合（dbt／Airflow 範例端到端）1
- 驗收方向：ETL 帳號 SELECT 核心表被拒；同批重跑 0 筆新事件；壞列 `REJECTED` 附欄位錯誤且同批好列照常；`backfill` 列不建 Enrollment；先到事件、後到客戶時最終 `APPLIED`

## 待甲方決定／需要調查

- 待決定：1 放在哪個版本（§4）；2 確認不提供方案 B（客戶不取得核心表寫權）；3 ETL 匯入的事件預設可否觸發旅程（建議：可，回填需自行標 `backfill`）；4 收件區成功列保留期（變數 `CDP_LANDING_RETENTION`，值由甲方定）；5 事件找不到客戶的重試上限與間隔是否採團隊建議值（同 D-027）
- 需調查：1 目標客戶實際的 ETL 工具與寫入方式（`COPY`、`INSERT`、單批大小、多久跑一次）；2 dbt-postgres 寫入「既有、非自己擁有的表」的做法與所需權限（incremental `append`、自訂 materialization 或 post-hook），只有欄位級 INSERT 是否足夠；3 代管雲端 PostgreSQL 給外部 ETL 連線的方式（網路白名單、TLS、連線數限制、是否經 PgBouncer）；4 百萬筆回填的吸收時間（併入 D-038 壓測）

## 風險

| 風險 | 對策 |
|---|---|
| 回填量大時收件區積壓，即時事件被拖慢 | `event_import` 先吸收 `backfill = false`；壓測量測（需調查 4） |
| ETL 誤把匯入時間填成 `occurred_at`（spec 01 §6） | 文件與 `batch_summary` 顯示 `occurred_at` 範圍供 ETL 自檢 |
