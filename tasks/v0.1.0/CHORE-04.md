# 派工單 CHORE-04

- **類型**：chore
- **負責**：architect
- **目標**：文件對齊 FIX-03b 已合併行為，並把 FIX-03 節收斂為已完成
- **背景**：PR #42 review 列出 9 處待對齊（`gh pr view 42 --comments` 的「FIX-03b 合併後需要對齊的文件」表），涵蓋 spec 07 §10、spec 05 第 11 行、DESIGN-01 做法 #4／#5、例外分流表、「設定」表與「FIX-03」節。
  只改文件，不改程式；內容以已合併程式為準。
- **設計依據**：docs/design/DESIGN-01.md；docs/spec/05-delivery-pipeline.md；docs/spec/07-database-schema.md；PR #42 review
- **相依**：無
- **負責檔案**：
  - `docs/design/DESIGN-01.md`
  - `docs/spec/05-delivery-pipeline.md`
  - `docs/spec/07-database-schema.md`
- **禁區**：`api/`、`web/`、`scripts/`、`docs/decisions.md`、README.md、BOARD.md、.env.example；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] PR #42 review 表中 9 處皆已對齊，與已合併的 `OutboxRelay`、`DeliveryConsumer`、`RabbitTopology` 行為一致
  - [ ] DESIGN-01 的「FIX-03」節縮為已完成並引用 PR #32、#42；「設定」表 FIX-03 列併回一般列；仍 ≤ 60 行
  - [ ] 不殘留「FIX-03 起」這類過渡字樣
- **驗證指令**：`make check`
- **回報檔**：`reports/CHORE-04.json`

## 執行資訊（task.py 產生）

- Issue：#45
- 分支：`chore/chore-04`（從 develop 開出）
- 工作目錄：`.worktrees\CHORE-04`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1：PR #42 review 原表（manager 轉錄）另有兩處未涵蓋，請補：
  1. DESIGN-01 做法 #5（或做法 #4 的 relay 說明）：relay 的 `FaultPoint` 例外只結束該批、不 reschedule（模擬 crash，下一輪重推，靠 consumer 冪等）。
  2. spec 07 §10 `effect_outbox` 列：`publish_count` 只在 `markPublished` 時遞增。
