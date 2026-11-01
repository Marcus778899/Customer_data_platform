# 派工單 FIX-06

- **類型**：fix
- **負責**：frontend
- **目標**：session 失效被導回登入時，登入頁顯示「登入已逾時，請重新登入」
- **背景**：UAT v0.1.0 F-1（major）：已登入使用者的請求收到 401 後，導向 `/login?next=…` 正確，但登入頁是空白表單沒有任何說明（見 docs/uat/v0.1.0.md F-1、截圖 docs/uat/v0.1.0/s19-session-expired.png）。
  規格 docs/ui/enrollment-trace.md 第 27 行「登入」列：session 逾時顯示「登入已逾時，請重新登入」；設計稿 `login-session-expired` 區段。
  直接開登入頁、或從未登入就被導向時，不顯示此提示；回原頁（含 `?q=`）的行為維持不變。
- **設計依據**：docs/ui/enrollment-trace.md；docs/ui/enrollment-trace/index.html；docs/contracts/journey-engine.md §1.2
- **相依**：無
- **負責檔案**：
  - `web/src/`（`styles/tokens.css` 除外）
  - `web/tests/unit/`
- **禁區**：`web/tests/acceptance/`、`web/src/styles/tokens.css`、web 建置設定檔、api/、.env.example；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `web/tests/acceptance/session-expired/`
- **驗收條件**：
  - [ ] 已登入（`/me` 曾成功）後任一請求 401 → 導向登入頁並顯示「登入已逾時，請重新登入」；登入後回原頁（含查詢字串）
  - [ ] 未登入直接進站或直接開 `/login` → 不顯示逾時提示
  - [ ] 登入錯誤時的訊息（帳號或密碼不正確等）與逾時提示不會同時出現或互相覆蓋
  - [ ] 既有前端驗收與單元測試全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/FIX-06.json`

## 執行資訊（task.py 產生）

- Issue：#57
- 分支：`fix/fix-06`（從 develop 開出）
- 工作目錄：`.worktrees\FIX-06`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（UAT 重現細節，見 docs/uat/v0.1.0.md F-1「重現細節」）：分頁開著時列表 401 會正確顯示提示；**重新載入頁面（或書籤進入、關閉後重開）時 `/auth/me` 回 401**，`App.tsx` 一律設 `expired=false`，提示消失——這才是 F-1 的實際成因。
- 修正方向（做法由 frontend 決定）：前端記住「曾經登入」（例如登入成功或 `/me` 200 時寫入瀏覽器儲存的旗標，登出時清除；不得存任何帳密或 session 值），`/me` 401 時若有此旗標即視為逾時並清除旗標。
- qa 先補驗收：登入後「重新渲染整個 App（模擬重新載入，保留瀏覽器儲存）」→ `/me` 401 → 顯示逾時提示、登入後回原頁；從未登入過 → `/me` 401 → 不顯示；登出後重新載入 → 不顯示；逾時提示只出現一次（登入成功後再遇未登入不重複顯示）。既有 bf9a1e3 測試保留。
