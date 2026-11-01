# 派工單 DESIGN-04

- **類型**：docs
- **負責**：architect
- **目標**：提出「客製資料整合以 PostgreSQL 與核心解耦」的方案比較與建議（只出方案，不實作）
- **背景**：D-051（代管、單一租戶、主打資料整合）、D-052（實際串接延後；整合以 PostgreSQL 解耦，ETL 如 Airflow、dbt 容器直接寫入的情境比第三方 API 更常見）。甲方要團隊意見。目前只有 `POST /events` 攝取 API（spec 01）；v0.2.0 的 ProfileStore 設計在 DESIGN-03（`.worktrees/DESIGN-03/docs/contracts/profiles.md`）。
- **設計依據**：`docs/spec/01-event-contract.md`、`docs/spec/07-database-schema.md`、DESIGN-03 契約、`docs/decisions.md` D-037、D-051、D-052
- **相依**：無
- **負責檔案**：
  - `docs/design/DESIGN-04.md`
- **禁區**：其他文件與程式（契約、spec 的修改等方案定案後另開單）；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] 至少比較三種做法（例：ETL 寫入 staging／landing schema 由核心輪詢吸收、ETL 直接寫 profile 與事件表、ETL 一律走 HTTP API），各列優缺點：資料驗證與髒資料隔離、去重與冪等、歸戶時機、觸發旅程、錯誤回報給 ETL 方、回填大量歷史資料、權限與 schema 演進
  - [ ] 給建議做法：核心與 ETL 之間的「介面」長什麼樣（表、欄位類別、狀態欄、誰能寫哪裡）、核心怎麼吸收（呼應 D-037 輪詢）、與既有事件 API 的關係
  - [ ] 列出對 DESIGN-03（ProfileStore、v0.2.0）現在就要預留的事項，以及可以延後的事項
  - [ ] 建議放在哪個版本、大約幾張單；需調查與待決定分開列
  - [ ] 全文精簡（80 行內），用甲方看得懂的語言寫一段摘要
- **驗證指令**：`make check`
- **回報檔**：`reports/DESIGN-04.json`

## 執行資訊（task.py 產生）

- Issue：#81
- 分支：`docs/design-04`（從 develop 開出）
- 工作目錄：`.worktrees\DESIGN-04`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-19

- 追加（D-053，2026-10-19）：
  - 入口不只 ETL：第三方轉接器（呼叫廠商 API 定期拉、接廠商轉發的 Webhook、廠商定期匯出檔）也寫進同一個 PostgreSQL 入口，方案要涵蓋
  - 新增出口：把分群後的受眾名單推給客戶現有的第三方行銷平台（反向 ETL），提方案與建議版本
  - 參考研究：`docs/research/line-and-vendors.md`（researcher 進行中；若完成前已交稿，研究結果出來後再修訂）
  - 行數上限放寬到 120 行

## 追加 2026-10-19

- 追加（qa FAIL，2026-10-19）：
  1. 預留對象改為 DESIGN-03 的 `ProfileWriter`（`ProfileService` 是 Controller 層）
  2. 研究中標為推論或查無的項目不可寫成事實（手機雜湊、同 provider、代理平台聯絡人 ID）：改為「官方文件查無，需確認」或標推論；補 LINE 受眾上傳的地區限制
  3. 版本直接寫版號與前提（收件區：v0.3.0 之後的下一版，見 D-055、D-059）
  4. 歸戶：寫清楚客戶列與事件列誰會建客戶、找不到時的行為，與 v0.2.0 現行（攝取自動建空白客戶）的關係；收件事件的 `EXTERNAL_SYSTEM` 簽章驗證如何以 DB 帳號憑證取代
  5. 介面表不帶 tenant_id 的例外，與 spec README「所有實體必帶 tenantId」的關係寫明（DESIGN-03 architect 會在其決定 #1 註明例外）

## 追加 2026-10-19

## D-063 定案後修訂

D-063 定案 v0.2.0 移除 tenant_id、廢除 `CDP_TENANT_ID`。DESIGN-04.md 做法 #12（tenant_id 例外）改寫為「全系統不帶 tenant_id」，回到多租戶的說明引用 D-063；匯出表（audience_export、audience_export_member）不帶 tenant_id；其他提到 `CDP_TENANT_ID` 處一併清。spec／契約的清理由 DESIGN-03 負責，本單只改 DESIGN-04 自己的負責檔案，引用即可
