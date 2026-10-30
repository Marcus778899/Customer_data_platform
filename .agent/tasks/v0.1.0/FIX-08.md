# 派工單 FIX-08

- **類型**：fix
- **負責**：frontend
- **目標**：375px 窄螢幕下列表與發送歷史的說明一行顯示、查詢表單欄位間距與設計稿一致
- **背景**：UAT v0.1.0 F-3（D-028 納入）：「共 1 筆，依進入時間新到舊」「已載入 1 筆，新到舊」逐字換行成窄欄、卡片被擠到下方；查詢表單欄位之間空白異常大。見 docs/uat/v0.1.0.md F-3、截圖 docs/uat/v0.1.0/s15-narrow-list.png、s16-narrow-detail.png。設計稿窄螢幕狀態為準。
- **設計依據**：docs/ui/enrollment-trace.md；docs/ui/enrollment-trace/index.html；web/src/styles/tokens.css
- **相依**：無
- **負責檔案**：
  - `web/src/`（`styles/tokens.css` 除外）
  - `web/tests/unit/`
- **禁區**：`web/tests/acceptance/`、`web/src/styles/tokens.css`、web 建置設定檔、api/、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `web/tests/acceptance/narrow-layout/`
- **驗收條件**：
  - [ ] 375px 寬：列表頁與詳情頁發送歷史的筆數說明不逐字換行，卡片在說明下方正常寬度
  - [ ] 375px 寬：查詢表單欄位間距與設計稿一致，無異常空白
  - [ ] 375px 寬無橫向捲動；桌面寬度外觀不變
  - [ ] 既有前端驗收與單元測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-08.json`

## 執行資訊（task.py 產生）

- Issue：#61
- 分支：`fix/fix-08`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-08`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
