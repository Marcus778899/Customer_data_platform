# 派工單 FIX-05

- **類型**：fix
- **負責**：backend
- **目標**：事件派發重試設定的啟動驗證，與解析失敗路徑的單元測試
- **背景**：來源 PR #32 review、CHORE-03 回報。`.env` 填錯（例如 `ENGINE_DISPATCH_MAX_ATTEMPTS=0`、退避為負或初始大於上限）目前會讓第一次失敗就 EXHAUSTED 或不停空轉，應在啟動時就拒絕。D-027 為預設值。
  `api/pom.xml` 已有 `spring-boot-starter-validation`（DESIGN-01 做法 #8）。錯誤訊息只寫設定名稱，不寫值。
- **設計依據**：docs/spec/07-database-schema.md §11；docs/design/DESIGN-01.md「FIX-03」節
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/ingest/DispatchProperties.java`
  - `api/src/test/java/com/cdp/ingest/`
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、`api/src/main/resources/`、`api/src/test/resources/`、其他檔案與套件、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/ingest/`
- **驗收條件**：
  - [ ] `maxAttempts < 1`、`backoffInitial` ≤ 0、`backoffMax` < `backoffInitial` 任一成立 → 啟動失敗，訊息指出 `cdp.engine.dispatch.*` 設定名、不含值
  - [ ] D-027 預設值可正常啟動
  - [ ] 單列解析失敗（`UndispatchedEvent.event` 為 null）→ 記一次失敗並標 EXHAUSTED、同批其他列照常派發，有 `EventDispatcher` 單元測試
  - [ ] 既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-05.json`

## 執行資訊（task.py 產生）

- Issue：#43
- 分支：`fix/fix-05`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-05`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（manager 修正驗收條件）：`backoffInitial` = 0 允許（代表下一輪輪詢就重試；`application-test.yml` 用 PT0S 讓既有測試在同一時鐘下立即重跑），只拒絕負值。條件 1 改為：`maxAttempts < 1`、`backoffInitial` 為 null 或負值、`backoffMax` 為 null 或小於 `backoffInitial` → 啟動失敗。
- qa：`DispatchPropertiesStartupAcceptanceTest` 的 `backoffInitialZero` 改為斷言可啟動（其餘案例不變）。worker：`DispatchProperties` 改為只拒絕負值，`make check` 須全過；不需改 `application-test.yml`。
- 不計 qa FAIL（驗收條件本身與既有測試設定衝突）。
