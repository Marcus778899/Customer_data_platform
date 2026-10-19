# 派工單 FIX-03a

- **類型**：fix
- **負責**：backend
- **目標**：引擎穩健：事件派發退避重試與放棄、推進缺資料轉 FAILED、拒收 U+0000、effect_type 明確對照
- **背景**：做法與驗收要點見 docs/design/DESIGN-01.md「FIX-03」節前三列（派發重試、推進缺資料、輸入與對照）；規則見 docs/spec/07-database-schema.md §11（V3 已合併）。決策 D-024、D-026（多旅程部分失敗：成功的保留）、D-027（預設值）。
  補充（CHORE-02 review）：上限語意＝總共嘗試 `ENGINE_DISPATCH_MAX_ATTEMPTS` 次；退避先與上限比較再相乘，避免 Duration 溢位；`lockUndispatched` 逐列解析，單列解析失敗直接走 EXHAUSTED，不卡整批。
  FIX-03b（relay／consumer）在本單合併後才開；本單不碰 `J/delivery/`、`EngineTestSupport`。
- **設計依據**：docs/design/DESIGN-01.md 做法 #12、「FIX-03」節；docs/spec/07-database-schema.md §10、§11；docs/contracts/journey-engine.md §3.2、§5
- **相依**：無（CHORE-02 已合併）
- **負責檔案**：
  - `api/src/main/java/com/cdp/ingest/`
  - `api/src/main/java/com/cdp/engine/runtime/`
  - `api/src/main/java/com/cdp/persistence/JdbcEventStore.java`、`api/src/main/java/com/cdp/persistence/JdbcStateStore.java`
  - `api/src/test/java/com/cdp/{ingest,engine,persistence}/`
  - `api/src/main/resources/application.yml`、`api/src/test/resources/application-test.yml`（只限 `cdp.engine.dispatch.{max-attempts,backoff-initial,backoff-max}`）
  - `.env.example`（只新增 `ENGINE_DISPATCH_MAX_ATTEMPTS`=5、`ENGINE_DISPATCH_BACKOFF_INITIAL`=PT10S、`ENGINE_DISPATCH_BACKOFF_MAX`=PT10M，D-027）
- **禁區**：`api/src/test/java/com/cdp/acceptance/`、`architecture/`、`api/pom.xml`、migration、`J/delivery/`、其他套件與 persistence 其他檔案；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `api/src/test/java/com/cdp/acceptance/ingest/`
- **驗收條件**：
  - [ ] 綁定持續失敗：未達上限前 `next_dispatch_at` 依退避遞增、後續事件照常派發；達上限 → `EXHAUSTED`、無 Enrollment；中途恢復 → `DISPATCHED` 且不重建；多旅程部分失敗依 D-026
  - [ ] 喚醒時定義或 open trace 不存在 → Enrollment `FAILED`、軌跡有 `decision_reason`，不丟例外、不影響同批其他筆
  - [ ] 事件含 U+0000 字串 → 400 `VALIDATION_FAILED`，`errors` 指出欄位；`effect_type` 為明確對照並有單元測試
  - [ ] 既有測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-03a.json`

## 執行資訊（task.py 產生）

- Issue：#31
- 分支：`fix/fix-03a`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-03a`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1：worker 實作 03e8ba0 完成；驗收測試 `IngestAcceptanceBase.instantOf` 把 timestamptz 強轉 `OffsetDateTime`（pgjdbc 實際回 `java.sql.Timestamp`），5 個測試 ClassCastException。qa 修 `instantOf`（相容 Timestamp／OffsetDateTime），不改斷言；修完 worker 重跑 `make check` 並把回報改為 done。不計 qa FAIL。
- 注意：`application.yml` 對三個新變數沒有預設值，甲方須在 .env 補值後才能 `make api-run`（D-027 預設值見 .env.example）。
