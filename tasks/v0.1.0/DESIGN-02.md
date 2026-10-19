# 派工單 DESIGN-02

- **類型**：docs
- **負責**：architect
- **目標**：補齊 BE-02 開工前與 review 累積的契約、設計缺口
- **背景**：BE-01、BE-04、FIX-01 review 與 qa 累積的待補項目（見 PR #13、#14、#16 的 review 留言）；BE-02 要用 outbox 的 effect_type／payload，目前文件未定義。
  另：`CDP_TRACE_PLAIN_KEYS` 設計上空值＝全遮，但 `scripts/check_env.py` 把空值視為未填而擋開單（scripts 不可改）；請在設計內提出可填的「全遮」寫法並同步 .env.example 說明。
- **設計依據**：docs/design/DESIGN-01.md；docs/contracts/journey-engine.md；docs/spec/05-delivery-pipeline.md、07-database-schema.md
- **相依**：無
- **負責檔案**：
  - `docs/contracts/journey-engine.md`
  - `docs/design/DESIGN-01.md`
  - `docs/spec/`
  - `.env.example`（只改註解或「全遮」的寫法說明，不新增變數）
- **禁區**：程式碼（`api/`、`web/`）、`scripts/`、`docs/decisions.md`、README.md、BOARD.md；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] spec 07 §10（或契約）定義 effect_outbox 的 effect_type 與 payload 鍵，BE-02 可直接照做
  - [ ] 契約 §5 補 405／406／413 等 4xx 對應的 code 表（不得與已合併行為矛盾；若需改程式，列為後續實作項）
  - [ ] DESIGN-01 補：單一 `ApiExceptionHandler`、錯誤一律 `ApiException`＋`ErrorCode`；StateStore.save 單一交易鏈；「待決定1/2/3」改引用 D-019～D-021
  - [ ] `CDP_TRACE_PLAIN_KEYS` 的「全遮」寫法定案並與 check_env 相容
  - [ ] 列出 v0.1.0 剩餘的後續實作項（例：BE-04 review 五項、poison row、EventDispatcher 重試上限）與建議歸屬的派工單，需甲方決定的列「待決定」
- **驗證指令**：`make check`
- **回報檔**：`reports/DESIGN-02.json`

## 執行資訊（task.py 產生）

- Issue：#18
- 分支：`docs/design-02`（從 develop 開出）
- 工作目錄：`.worktrees\DESIGN-02`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
