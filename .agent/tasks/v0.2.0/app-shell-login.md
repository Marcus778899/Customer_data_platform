# 派工單 app-shell-login

- **類型**：feat
- **負責**：frontend
- **目標**：產品外框（導覽：旅程、客戶、受眾〔規劃中〕）、email 登入／修改密碼／登出、受眾佔位頁與 403 無權限頁，API client 帶 `X-XSRF-TOKEN`
- **背景**：即 DESIGN-03 派工表的 FE-02（驗收為 QA-03）。後端登入（BE-06）尚未實作，本單一律對 `auth.md` 契約 mock。D-049 導覽與產品名、D-034 受眾佔位、D-045 登入限速（429 倒數）、D-051 無工作區。驗收測試由 qa 在本分支先寫好，不可改。
- **設計依據**：`docs/contracts/auth.md`（§1～§3）；`docs/design/DESIGN-03.md` 派工表批 1；`docs/ui/journey-platform.md`（登入、修改密碼、外框、受眾佔位、無權限、窄螢幕與無障礙）；設計稿 `docs/ui/journey-platform/index.html`；`web/src/styles/tokens.css`
- **相依**：無，mock
- **負責檔案**：
  - `web/src/`（`web/src/styles/tokens.css` 除外）
- **禁區**：`web/src/styles/tokens.css`；`web/tests/acceptance/`；`web/package.json`、`package-lock.json`（需新套件先回報）；`api/`、`docs/`；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/shell/`
- **驗收條件**：
  - [ ] 登入、修改密碼的空／載入／錯誤／成功狀態與文案依 UI 規格；401 單一句、429 依 `Retry-After` 倒數、到 0 恢復
  - [ ] 外框：桌面左側欄、< 1024px 頂列＋選單；帳號區顯示姓名、角色、修改密碼、登出；無工作區切換
  - [ ] 受眾佔位寫「預計 v0.4.0」；403 整頁且導覽可用
  - [ ] 寫入請求帶 `X-XSRF-TOKEN`；`CSRF_INVALID` 重取 `/auth/me` 自動重試一次
  - [ ] 無障礙依 UI 規格（焦點、`role="alert"`／`status`、鍵盤可操作）
  - [ ] 驗收測試全綠；既有前端測試全綠
- **驗證指令**：`make check`
- **回報檔**：`reports/app-shell-login.json`

## 執行資訊（task.py 產生）

- Issue：#88
- 分支：`feat/app-shell-login`（從 develop 開出）
- 工作目錄：`.worktrees\app-shell-login`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-19

## 既有驗收測試與新登入衝突

v0.1.0 的 `web/tests/acceptance/session-expired/`、`integration/`、`main/`、`trace-page/` 依舊登入（帳號欄、`/me` 回 `{username}`）寫成，與 `auth.md` 新契約衝突。由 qa 在本分支依 `auth.md` 改寫這些既有驗收（只改登入／登出／`/auth/me` 相關部分，不改測試意圖），改寫後同列為本單驗收測試，worker 不可改：
  - `web/tests/acceptance/session-expired/`、`integration/`、`main/`、`trace-page/`
「既有前端測試全綠」以改寫後的版本為準。

## 追加 2026-10-19

## qa FAIL（@4ced498）待修

先由 qa 修驗收測試（A）：
- `shell.test.tsx:39`：combobox 檢查限縮在導覽／外框內；新增回歸測試「`/` 無 `?q=` 時仍有狀態下拉」（D-015）
- `login.test.tsx:278-293`：登入成功後資料請求改回成功，斷言穩定落在 `/?q=abc`；負載下重跑至少 10 次
- `trace-page.test.tsx` 400 測試：email 改為空字串
再由 worker 修：
1. `web/src/pages/SearchPage.tsx:178-190`：還原為永遠顯示狀態篩選（D-015）
2. `web/src/pages/LoginPage.tsx:128-129`：429 文案改為「請在 N 秒後再試。」（倒數後不加空格，依設計稿）
3. `web/src/api/client.ts:25-30` 的 401 合併可保留，但不得作為測試穩定的依據
另：403 頁非檢視者角色文案沿用目前寫法，之後請 designer 補進 UI 規格
