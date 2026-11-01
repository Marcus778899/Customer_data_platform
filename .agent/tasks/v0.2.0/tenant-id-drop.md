# 派工單 tenant-id-drop

- **類型**：refactor
- **負責**：backend
- **目標**：依 D-063 從 schema、程式與單元測試移除 `tenant_id`／`tenantId`，對外 API 不變
- **背景**：即 DESIGN-03 派工表的 REFACTOR-01（驗收為 QA-09）。D-063、D-064：`.env` 的 `CDP_TENANT_ID` 甲方暫留，本單合併後由甲方刪除。本單與 BE-05 同檔，BE-05 等本單合併才開工。驗收測試（`schema/` 新增、`delivery/`／`engine/`／`query/` 既有的 tenant 改寫）由 qa 在本分支先寫好，不可改。
- **設計依據**：`docs/design/DESIGN-03.md` 做法 #1 與派工表批 1；`docs/spec/07-database-schema.md` §8、§12「V5」；`docs/spec/02-enrollment-model.md` §2；`docs/spec/04`、`05`；`docs/contracts/journey-engine.md` §1
- **相依**：無
- **負責檔案**：
  - `api/src/main/resources/db/migration/V5__drop_tenant_id.sql`（新）
  - `api/src/main/java/com/cdp/` 中引用租戶的檔（`common/CdpProperties` 刪除、`delivery/`、`engine/`、`ingest/`、`journey/`、`persistence/`、`query/`）
  - `api/src/test/java/com/cdp/` 單元測試（`acceptance/` 除外）
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`
  - `Makefile`（:16 註解）、`.env.example`（刪 `CDP_TENANT_ID`）
- **禁區**：`api/src/test/java/com/cdp/acceptance/`；V1～V4 migration；對外 API 行為與回應格式；`web/`、`docs/`、`tools/`；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/schema/`
  - `api/src/test/java/com/cdp/acceptance/delivery/`、`engine/`、`query/`（qa 改寫的部分）
- **驗收條件**：
  - [ ] V5 依 spec 07 §12：單一交易，7 張表刪欄、`event` PK 改 `(event_id)`、7 個索引以原名重建；V4 灌資料升 V5 後列數不變
  - [ ] `grep -rni tenant api/src/main` 無殘留（回報附輸出；必要的註解說明除外並列出）
  - [ ] 程式不再讀 `CDP_TENANT_ID`／`cdp.tenant-id`，`.env` 有沒有這個值都能啟動
  - [ ] 驗收測試全綠；既有測試全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/tenant-id-drop.json`

## 執行資訊（task.py 產生）

- Issue：#87
- 分支：`refactor/tenant-id-drop`（從 develop 開出）
- 工作目錄：`.worktrees\tenant-id-drop`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-19

## 驗收測試漏改：ingest/

worker 回報（9cf9d0f）：`api/src/test/java/com/cdp/acceptance/ingest/EventDispatchRetryAcceptanceTest.java` 仍用含 tenantId 的 7 參數 `.start(...)` 與 `getArgument(1)`，移除 tenantId 後 test-compile 失敗。由 qa 在本分支改寫（不改測試意圖），改寫後 `acceptance/ingest/` 同列本單驗收測試，worker 不可改。worker 實作不需再改。

## 追加 2026-10-19

## qa FAIL（@c9a5486）待修

1. `.env.example:29-30` 刪除 `CDP_TENANT_ID` 與其註解（派工單負責檔案已列）。qa 先補一個驗收測試確認 `.env.example` 不含 `CDP_TENANT_ID`，worker 再刪
其餘檢查已通過，實作不需他改

## 追加 2026-10-20

## architect review changes（@e2e9061）待修

1. `api/src/main/java/com/cdp/journey/JourneyService.java:136`：`new JourneyDefinition(request.journeyId(), null, 0, …)` 刪錯參數（刪了 journeyId、留下原 tenant 位置的 null）→ 改為 `new JourneyDefinition(request.journeyId(), 0, request.name(), …)`
2. `JourneyServiceTest`（單元測試，歸 worker）補斷言：帶 journeyId 時，傳進 validate 的 definition 的 `journeyId` 等於請求值；先確認此斷言在修正前失敗
3. 全面再檢查一次其他建構子呼叫有無同類「刪錯位置」的情形（record 參數順序），回報檢查方式
