# 設計：客製資料整合以 PostgreSQL 與核心解耦（DESIGN-04，只出方案）

- 依據：D-037、D-051、D-052、D-053、D-055、D-058、D-059（後三者待決定）；spec README「識別碼」「多租戶」；spec 01 §3～§7、§9；spec 06 §7～§8；spec 07 §2、§6、§11、§12；DESIGN-03 做法 #1、#4、#5，`profiles.md` §1～§2、`auth.md` §2；研究 `docs/research/line-and-vendors.md`（下稱「研究」）
- 現況：唯一入口 `POST /v1/events` → `EventValidator.validate` → `EventIngestionService.ingest` → `EventStore.insertIfAbsent` → `EventDispatcher` 輪詢扇出；沒有出口
- 標記：「推論」= 研究或本文推得、官方文件未寫；「查無，需確認」= 官方文件找不到

## 摘要（給甲方）

平台定位是「資料中樞」（D-053）：把客戶各處的資料收進來、認出是同一個人、分好群，再把名單交給客戶原本在用的行銷平台去發送。

- **收資料**：資料庫裡開一個「收件區」。客戶的 ETL（Airflow、dbt），以及我們替各家廠商寫的「轉接器」（定時去廠商 API 拉資料、接收廠商轉來的通知、讀廠商定期匯出的檔案），全都只能把資料「放進」收件區，碰不到平台自己的資料。平台每隔幾秒取件，用和現有事件 API 同一套規則檢查、去重、認人，再把每一列的結果（成功、重複、錯在哪個欄位）寫回原列，送件方自己查得到。客戶資料可以建立新客戶；事件資料只掛到已認得的客戶，認不到就等一段時間再放棄並列出原因。好處：髒資料擋在門外、重送不會重複、廠商斷線或平台更新互不影響、匯入舊資料不會誤發訊息。代價：資料約晚幾秒生效。
- **送名單**：分群結果先存成一份「當下名單」，可以三種方式交出去：①下載 CSV；②開一個唯讀「出件區」，讓客戶自己的工具來拿；③平台直接推送（先做 LINE，其他廠商要先拿到對方的 API 文件）。LINE 官方文件只列「LINE 用戶 ID」與廣告識別碼兩種名單，手機號碼查不到支援，要再確認；LINE 用戶 ID 每個開發者帳號（provider）都不同，推送時是否必須同一個 provider 官方沒寫，我們推論必須相同。LINE 名單功能只開放日本、泰國、台灣建立的官方帳號。每家廠商要哪種識別碼都要設定對應，對應不到的人會列出「對應率」。
- **建議版本**（前提見 §6）：收件區 v0.4.0（約 6＋3 張單）；名單出口跟「受眾分群」同一版（約 6 張）；LINE 推送再下一版（約 4 張）。

## §1 入口方案比較

| 面向 | A 收件區＋核心輪詢吸收（建議） | B ETL 直接寫核心表（`profile`、`event`） | C ETL 一律走 HTTP API | D 核心去讀客戶自己的表／view |
|---|---|---|---|---|
| 驗證與髒資料隔離 | 核心逐列套 `EventValidator`／`profiles.md` §1.1；壞列標 `REJECTED` 留在收件區，不進核心 | 只剩 DB 約束；格式、正規化、遲到規則全被繞過，壞資料直接污染 | 同 A（同一套驗證） | 同 A，但對方表結構不受我方控制，欄位一改就壞 |
| 去重與冪等 | 事件以 `(source_id, external_key)` 推導 `event_id`，沿用 `event` 主鍵；重跑同批 = `DUPLICATE` | ETL 須自己產 ULID 並保證穩定，做不到就重複 | 同 A，但 ETL 須自產穩定 ULID | 須記「讀到哪」水位；對方更新舊列時難判斷 |
| 歸戶時機 | 吸收時依識別碼查 `profile_identifier`（§2 #5） | ETL 須先知道平台的 `profile_id`，實務上不知道 | 同 A（在 API 內） | 同 A |
| 觸發旅程 | 寫進 `event` 後由 `EventDispatcher` 照常派發；可標 `backfill` 不建 Enrollment | 繞過 `dispatched_at` 欄位規則，易漏派或重派 | 照常 | 照常 |
| 錯誤回報給 ETL | 結果寫回同列（狀態＋逐欄錯誤），ETL 用 SQL 查；可做 Airflow sensor | 只有 SQL 例外，整筆交易失敗 | HTTP 回應（spec 01 §9 逐筆結果）；ETL 要寫程式處理 | 我方日誌，對方看不到 |
| 大量回填 | `COPY` 進收件區很快；核心分批吸收，量大時延遲變長但不擋其他迴圈 | 快，但無驗證 | 慢：每批 500 筆、要寫 HTTP 客戶端 | 快，但要長時間持有對方連線 |
| 權限與 schema 演進 | ETL 帳號只有收件區欄位級 INSERT；核心表可自由改；收件區只做「加欄位」 | ETL 須有核心表寫權；核心每次改表都破壞客戶 ETL | 最乾淨；API 版本化 | 我方要讀對方資料庫，權限與網路由客戶掌控 |
| dbt／Airflow 友善度 | 高（寫表是它們的本行）；dbt 寫入非自己擁有的表需調查 2 | 高 | 低（dbt 不會打 API） | 高，但部署耦合 |

結論：A（D-058）。B 違反「核心不讓外部直寫」的基本隔離；C 留給客戶自家 App／後端的即時事件；D 只在客戶不肯改 ETL 時當轉接（之後另議）。

## §2 建議做法（方案 A）

| # | 項目 | 設計 |
|---|---|---|
| 1 | 介面 = 兩張表 | `landing.profile_upsert`、`landing.event_import`；獨立 schema，由核心 Flyway 管理；核心表不出現在送件方視野 |
| 2 | 欄位類別 | **控制**：`seq` BIGINT identity（處理順序）、`batch_id`（送件方一次執行的代號，必填）、`source_id`（`DEFAULT current_user`，送件方不可寫）、`submitted_at` DEFAULT now()。**內容**（送件方寫）：事件 = `external_key`、`name`、`occurred_at`、`identifiers` JSONB、`properties` JSONB、`backfill` BOOL；客戶 = `identifiers` JSONB、`attributes` JSONB（語意同 `profiles.md` §2 淺層合併，`null` 刪鍵）；兩表皆有可 null 的 `source_payload` JSONB（轉接器放廠商原始資料，供對應規則修正後重放）。**狀態**（只有核心寫）：`status`、`errors` JSONB（同 `FieldError` 格式）、`profile_id`、`event_id`、`attempts`、`processed_at` |
| 3 | 狀態值 | `PENDING` → `APPLIED`／`DUPLICATE`／`REJECTED`（驗證失敗或等客戶逾限，`errors` 附原因碼）／`CONFLICT`（識別碼分屬不同客戶，不合併） |
| 4 | 認證與信任（取代 spec 01 §4 的 HMAC） | 收件列等同 `source_type = EXTERNAL_SYSTEM`；spec 01 §4 以 HMAC 簽章證明「是哪個外部系統送的」，收件區改由 **DB 登入憑證**證明：每個 ETL／轉接器一個 DB 帳號，`source_id` 由 `current_user` 填、送件方改不了 → 吸收時 `trust_level = VERIFIED`。簽章錯誤回 `403` 不落地（spec 01 驗收）的對應 = 沒有帳號或密碼錯誤連不上、無權限 INSERT 被拒。撤銷 = `ALTER ROLE … NOLOGIN`。帳密只在送件方的設定，連線限 TLS（需調查 3）。廠商 Webhook 自己的簽章由轉接器驗（§3），核心只信 DB 帳號 |
| 5 | 歸戶（誰建客戶） | **客戶列會建客戶**：識別碼依 `profiles.md` §1.1 正規化後查 `profile_identifier`——全部查不到 → 核心產生 ULID 建新客戶；都指向同一位 → 合併屬性、補新識別碼；分屬不同客戶 → `CONFLICT`（不合併、不寫入）。**事件列不建客戶**（spec 01 §5 `EXTERNAL_SYSTEM`「外部系統應該認得客戶」）：查到一位 → 寫事件；分屬多位 → `CONFLICT`；查不到 → 留 `PENDING`、`attempts + 1`，等同批或之後的客戶列到達（即 spec 01 §5 的「待處理佇列」），達上限轉 `REJECTED`（原因 `PROFILE_NOT_FOUND`）並告警。**與 v0.2.0 的關係**：v0.2.0 的 HTTP 攝取以呼叫端給的 `identity.profile_id` 自動建空白客戶（`profiles.md` §2），不做身分解析；收件區要的是「依識別碼查」——即 spec 01 §5 `ProfileStore.resolve`，在收件區那一版才實作，只給收件區用；HTTP 攝取維持 v0.2.0 行為，是否改為 resolve 另議 |
| 6 | 吸收（D-037 輪詢） | 新迴圈 `LandingImporter`：每 tick 先 `profile_upsert` 後 `event_import`，`WHERE status='PENDING' ORDER BY seq LIMIT n FOR UPDATE SKIP LOCKED`（partial index `WHERE status='PENDING'`）；每列一個交易：核心寫入與狀態回寫同交易；滿批續做（同 DESIGN-03 `CDP_LOOP_MAX_DRAIN`）；之後需要再加 LISTEN/NOTIFY |
| 7 | 與事件 API 的關係 | 同一條路：事件列轉成 `EventRequest`（`source_type = EXTERNAL_SYSTEM`）→ `EventValidator.validate` → `EventStore.insertIfAbsent` → `EventDispatcher`；客戶列 → `ProfileWriter`（DESIGN-03 做法 #4 寫入的唯一實作）。不另寫一套規則 |
| 8 | `event_id` | `external_key` 必填（來源的自然鍵，例 `order_id`、廠商事件 ID）；核心以 SHA-256(`source_id`, `external_key`) 取 128 位元編成 ULID 格式；`occurred_at` 改了重跑仍判為重複（spec 01 §7「穩定 id」） |
| 9 | 回填 | `backfill = true` → 寫入但不建 Enrollment（仍可喚醒既有等待，同 spec 01 §6 遲到規則）；未標但遲到 > 24h 已由現有 `LATE` 處理 |
| 10 | 回報與清理 | view `landing.batch_summary`（每個 `batch_id` 各狀態筆數、`occurred_at` 範圍）給送件方判斷成敗；`APPLIED`／`DUPLICATE` 列超過保留期由核心刪除，`REJECTED`／`CONFLICT` 保留到重送 |
| 11 | schema 演進 | 收件區只加可為 null 的欄位；要破壞相容時開新表（`event_import_v2`）並行一段期間；客戶自訂屬性都在 JSONB，不需改表 |
| 12 | `tenant_id` 例外 | spec README「`tenantId` 所有實體必帶」管的是核心實體；收件區的表與 §4 的 `outbound` view 是**介面**（待吸收的送件、對外唯讀投影），不是實體，**不帶** `tenant_id`——送件方不該知道租戶。由它產生的核心實體（`event`、`profile`、`profile_identifier`、§4 匯出快照表）照帶，值一律 `CDP_TENANT_ID`（DESIGN-03 做法 #1），規則仍成立。DESIGN-03 會在做法 #1 註明此例外。回到多租戶時：每個送件帳號屬於一個租戶（由 `source_id` 推導），出件 view 依讀取帳號的租戶過濾 |

## §3 第三方轉接器入口（D-053）

轉接器 = 我方或客戶外包寫的小程式，**與核心分開部署**在同一套客戶環境（D-051），以自己的送件帳號寫 §2 收件區；廠商憑證只在轉接器的設定（變數名定案後開單時列）。

| 種類 | 做法 | `external_key` 與 `batch_id` | 轉接器自己的狀態 |
|---|---|---|---|
| 定期拉廠商 API | 排程呼叫，依「上次拉到哪」增量取，逐筆轉成收件列 | 廠商紀錄 ID；`batch_id` = 該次執行 | 水位存在轉接器自有 schema（`connector_<名稱>`），核心不讀 |
| 接廠商轉發的 Webhook | 驗簽（LINE 官方要求先驗簽，研究 §1）→ 原文放 `source_payload`、對應後寫收件列 → 立即回 200 | 廠商事件 ID（無則需調查 5）；`batch_id` = 收件日 | 無 |
| 廠商定期匯出檔 | 取檔 → 整檔解析成功才 `COPY` 進收件區；整檔格式錯誤不寫入並告警 | 檔內紀錄 ID；`batch_id` = 檔名＋雜湊（同檔重送 = `DUPLICATE`） | 已處理檔清單（自有 schema） |

- 為何走收件區而非 HTTP API：①一處看成敗——非即時來源的結果都在 `batch_summary`；②不擴散金鑰——D-019 是單一攝取金鑰，轉接器的 DB 帳號只能 INSERT 收件區，外洩影響小；③核心停機不掉資料——核心部署或當機時轉接器照收，恢復後補吸收；④可重放——`source_payload` 留原文；⑤介面只有 SQL——轉接器可用任何語言。代價：生效延遲一個輪詢間隔；要求秒級反應的來源之後可改呼叫 HTTP API，不在本案

## §4 出口：受眾名單推送（D-053）

| 方案 | 說明 | 評估 |
|---|---|---|
| ① CSV 匯出 | 依目的地設定的欄位與格式產檔下載 | 通用；MAAC 官網列有 CSV 匯入（研究 §4），Omnichat、Super 8 未見匯入說明，查無，需確認（需調查 6）；最先做 |
| ② 出件區（`outbound` schema，唯讀 view） | 快照表的對外投影，客戶自己的反向 ETL／廠商來 SELECT | 客戶有資料團隊時最省事；與 ① 同版 |
| ③ 推送轉接器 | 核心呼叫廠商 API（介面 `AudiencePusher`：`destinationKind()`、`push(export) → PushResult`） | LINE 有公開 API，先做；台灣代理平台未見公開 API 文件（研究 §4），拿到規格才逐家做 |

| # | 項目 | 設計 |
|---|---|---|
| 1 | 匯出 = 靜態快照 | 每次匯出以 `SegmentEngine.materialize`（spec 06 §8）存成核心表 `audience_export`（`export_id`、`segment_id`、`segment_version`、`destination_id`、`status`、筆數、`external_ref`）＋`audience_export_member`（`export_id`、`profile_id`、`id_type`、`id_scope`、`id_value`、`match`），皆帶 `tenant_id`；三種出口讀同一份，可複盤「當時給了誰」（spec 06 §7）；② 是這兩張表的 view |
| 2 | 目的地 | 核心表 `destination`：`destination_id`、`kind`（`CSV`／`LINE_AUDIENCE`／之後各廠商）、`id_types`（依優先序）、`format`（例：手機輸出 `+886…` 或 `09…`）；憑證不進表，只在環境變數 |
| 3 | 狀態 | `PENDING` → `READY`（快照完成）→ `PUSHING` → `DONE`／`FAILED`；推送走既有 outbox → RabbitMQ → 冪等 consumer（D-006、spec 05），以 `export_id`＋分段序號冪等，失敗重試同 D-024 |
| 4 | 對應不到 | 客戶沒有目的地要的任何識別碼 → `match = UNMATCHED`，畫面與匯出摘要顯示對應率；不猜、不用其他識別碼替代 |
| 5 | 個資 | 匯出含明文個資，不套畫面遮罩（D-014、D-021 只管畫面）；權限、留存、稽核與行銷同意排除到該版設計時再問（D-059） |
| 6 | LINE 限制（研究 §2、§3） | 官方：上傳只收 userId 或 IFA（IFA 限企業申請）；手機號碼（含雜湊）查無，需確認；userId 每個 provider 不同；只有日本、泰國、台灣建立的官方帳號可建受眾；JSON 每次 ≤ 1 萬、上傳檔 ≤ 150 萬；每 channel ≤ 1,000 個受眾、保留 ≤ 180 天；long-lived token 每 channel 1 個、stateless 不限數量；module channel 不可用 long-lived。推論：推送的 userId 須與目的地官方帳號同 provider（官方未寫）→ 只輸出 `id_scope` 相符者；同一受眾＋目的地重用同一個 LINE 受眾；用 stateless token 不重發 long-lived（重發可能讓客戶現有廠商斷線） |

識別碼對應：

| 目的地 | 對方收什麼 | 我方來源 |
|---|---|---|
| LINE Audience | userId（官方）；同 provider 為推論；IFA（企業申請） | `LINE_USER_ID`＋`id_scope` = provider；IFA 需新類型（延後） |
| 代理平台 CSV（MAAC 等） | 依廠商匯入格式（需調查 6） | `PHONE`（存 E.164，依 `format` 轉）、`EMAIL`、`MEMBER_ID`、`LINE_USER_ID` |
| 代理平台 API | 未見公開文件，查無，需確認（需調查 6）；若用廠商自有聯絡人 ID 為推論 | 若需要：新類型 `PLATFORM_ID`＋`id_scope` = 平台名，由 §3 轉接器入口帶進來歸戶 |

## §5 對 DESIGN-03（v0.2.0）

| 現在就預留 | 可以延後 |
|---|---|
| `ProfileWriter`（做法 #4）以「請求物件 → 結果（套用／`CONFLICT`＋`FieldError` 清單）」回傳，不丟 HTTP 例外；`profile_id` 可由呼叫端給（§2 API）或由它產生（收件區新客戶）；HTTP 轉換留在 `ProfileService`／Controller | 收件區、送件帳號、`LandingImporter`、`batch_summary`、轉接器 |
| 攝取建空白客戶也經 `ProfileWriter`（做法 #4 已是），不放 Controller | 依識別碼查客戶（spec 01 §5 `ProfileStore.resolve`，§2 #5）；v0.2.0 仍要求 `profile_id` |
| `profile_identifier` 主鍵維持「一個識別碼只屬一位客戶」（已是），收件區衝突判定靠它 | `profile_identifier.id_scope`（加可 null 欄位，不動主鍵；記 LINE userId 屬哪個 provider）、`PLATFORM_ID` 類型（擴 CHECK） |
| 前端顯示 `IdentifierType` 遇到未知值要能顯示原值，不假設只有五種 | `backfill` 對應的 `dispatch_outcome` 新值（要改 V4 的 CHECK）；匯出快照表與 `outbound` view |
| 核心表與 schema 不對 `PUBLIC` 授權；新 DB 角色一律在 Flyway `afterMigrate` 管；做法 #1 註明 §2 #12 的例外 | 保留期清理、LISTEN/NOTIFY、送件指南與 dbt 範例 |

## §6 版本與單數

前提：D-055 選 A 或 B（受眾順延到 v0.5.0，DESIGN-03 拆版說明）、D-059 選 A；D-059 選 B 時收件區為 v0.3.0；D-055 選 C 時受眾維持 v0.4.0（D-034），出口版號各減一。

| 階段 | 版本 | 約張數 |
|---|---|---|
| 收件區核心 | v0.4.0 | 6：設計細化（契約 `landing.md`＋spec 07）1、BE 收件區＋客戶吸收＋歸戶 1、BE 事件吸收＋回填 1、qa 2、整合（dbt／Airflow 範例端到端）1 |
| 轉接器（檔案＋拉 API 骨架） | v0.4.0（排不下則 v0.5.0） | 3：BE 2、qa 1；個別廠商 Webhook／API 轉接器依客戶另開，每家約 2 |
| 出口 ①② | 與受眾同版：v0.5.0 | 6：設計 1、BE 快照＋CSV＋出件區 1、UI 1、FE 1、qa 1、整合 1 |
| 出口 ③ LINE | v0.6.0 | 4：設計 1、BE 1、qa 1、整合（測試用官方帳號）1 |

- 驗收方向：送件帳號 SELECT 核心表被拒、無帳號 INSERT 被拒；同批重跑 0 筆新事件；壞列 `REJECTED` 附欄位錯誤且同批好列照常；`backfill` 列不建 Enrollment；先到事件、後到客戶最終 `APPLIED`，客戶始終未到則 `REJECTED`（`PROFILE_NOT_FOUND`）；事件列不建客戶；同一快照重推 LINE 不重複建受眾；無對應識別碼者列為 `UNMATCHED`

## 待甲方決定／需要調查

- 待決定：已提 D-058（方向 A、不提供 B、匯入事件預設可觸發旅程、轉接器走收件區）、D-059（版本順序）。其餘依 D-059 建議到該版設計時再問：收件區成功列保留期（變數 `CDP_LANDING_RETENTION`）；事件等客戶的重試上限與間隔（建議同 D-027 採團隊值）；受眾之前是否先用屬性條件圈名單匯出（建議不提早）；誰能匯出明文名單與匯出檔期限（建議僅 `ADMIN`、留稽核；變數 `CDP_EXPORT_LINK_TTL`）；匯出是否一律排除不同意行銷者（建議排除）；LINE 推送授權方式（建議客戶自己的 channel＋stateless token）
- 需調查：1 目標客戶 ETL 工具與寫入方式（`COPY`／`INSERT`、批量、頻率）；2 dbt-postgres 寫入既有、非自己擁有的表的做法與權限，欄位級 INSERT 是否足夠；3 代管雲端 PostgreSQL 給外部連線的方式（白名單、TLS、連線數、PgBouncer）；4 百萬筆回填吸收時間（併入 D-038 壓測）；5 客戶 LINE Webhook URL 是否已被現有平台佔用（每 channel 僅一組，研究 §1）、各廠商轉發 Webhook 的驗簽方式與事件 ID；6 各代理平台名單匯入格式與 API（MAAC Open API、Omnichat、Super 8），需向廠商索取；7 廠商匯出檔交付方式（SFTP、物件儲存）與格式；8 LINE：手機號碼（含雜湊）能否上傳、推送 userId 是否須同 provider、module 與官方帳號 provider 是否須一致、既有受眾能否覆寫或移除成員（研究查無）；9 目標客戶官方帳號的建立地區（非日、泰、台不能建受眾）；10 IFA 申請條件；11 代管受託範圍與名單外送的契約約定（個資法第 4、20-1 條，法務）

## 風險

| 風險 | 對策 |
|---|---|
| 回填量大時收件區積壓，即時事件被拖慢 | `event_import` 先吸收 `backfill = false`；壓測量測（需調查 4） |
| 送件方把匯入時間填成 `occurred_at`（spec 01 §6） | 文件與 `batch_summary` 顯示 `occurred_at` 範圍供自檢 |
| 事件列先到、客戶列遲遲不來，`PENDING` 堆積 | 重試上限後 `REJECTED` 並告警；`batch_summary` 顯示等待中筆數 |
| 推送的 LINE userId 與目的地 provider 不符（推論會失效） | 只輸出 `id_scope` 相符者，不符計入 `UNMATCHED`；需調查 8 確認 |
| 明文名單外洩 | 權限與期限到該版設計時定（D-059）；匯出檔過期即刪，不長存 |
