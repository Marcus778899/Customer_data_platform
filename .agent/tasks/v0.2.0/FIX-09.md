# 派工單 FIX-09

- **類型**：fix
- **負責**：backend
- **目標**：修正 CI 間歇失敗：`DeliveryResilienceAcceptanceTest.consumer_cannotCreateTransaction_waitsRequeueDelay_noBusySpin_thenExactlyOnce` 第 451 行 `mockCount` 期望 0 實得 1
- **背景**：FIX-03b 合併（2026-10-18）後 develop 的 push CI 38 次失敗 12 次，全是這一個測試，與當次 commit 無關（多數是純文件 commit）。測試在 `finally` 先釋放佔住的連線，之後才斷言「DB 不可用期間沒有發送」；推測卡在等連線的 consumer 一拿到連線就送出，屬測試競態。但同時 `taskStatus` 為 null 而 mock 已有 1 筆，需確認產品是否有「發送了卻沒有任務紀錄」的問題。
- **設計依據**：`docs/contracts/journey-engine.md`、`docs/spec/05-delivery-pipeline.md`、FIX-03b 派工單與驗收測試
- **相依**：無
- **負責檔案**：
  - `api/src/main/java/com/cdp/delivery/`（只在確認是產品問題時修改）
- **禁區**：驗收測試目錄（由 qa 修改）；其他 api／web 程式；不可讀 .env
- **驗收測試**：
  - `api/src/test/java/com/cdp/acceptance/delivery/`
- **驗收條件**：
  - [ ] 回報寫明根因：測試競態或產品問題（附證據：log 或重現步驟）
  - [ ] 「DB 不可用期間沒有任務、沒有發送」在釋放連線前斷言，釋放後仍驗證恰好送一次
  - [ ] 若為產品問題，發送與任務紀錄的一致性符合 spec 05，並有測試涵蓋
  - [ ] 該測試類別在本機連跑 20 次全數通過（附指令與結果）
- **驗證指令**：`make check`；重複執行該測試類別 20 次
- **回報檔**：`reports/FIX-09.json`

## 執行資訊（task.py 產生）

- Issue：#80
- 分支：`fix/fix-09`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-09`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
