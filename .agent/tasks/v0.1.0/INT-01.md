# 派工單 INT-01

- **類型**：feat
- **負責**：frontend
- **目標**：軌跡查詢頁改接真實後端 API（登入、列表、詳情、發送紀錄續查），移除 mock
- **背景**：FE-01 已依契約做 mock（`web/src/mock/`、`main.tsx` 的 `VITE_API_MOCK`）；BE-03（查詢）、BE-04（登入 session）已合併。DESIGN-01 派工順序 3。
  移除 mock 後 `VITE_API_MOCK` 不再需要。FE-01 review 待辦一併處理：403 時 ErrorBox 顯示「沒有權限」且不提供重試。
  本機走 `make infra-up`＋`make api-run`＋`make web-dev`（vite 已把 `/api` 代理到後端），cookie 是 Secure，只在 localhost 或 HTTPS 下可用。
- **設計依據**：docs/contracts/journey-engine.md §1.2、§4、§5；docs/ui/enrollment-trace.md；docs/ui/enrollment-trace/index.html
- **相依**：無（BE-03、BE-04 已合併；BE-02 未合併只影響發送紀錄有無資料）
- **負責檔案**：
  - `web/src/`（`styles/tokens.css` 除外）
  - `web/tests/unit/`
- **禁區**：`web/tests/acceptance/`、`web/src/styles/tokens.css`、web 建置設定檔（package.json、vite.config.ts、tsconfig、eslint 設定）、api/、.env.example；新增套件需回報 manager；不可讀 .env
- **驗收測試**：qa 先寫，worker 不可改
  - `web/tests/acceptance/integration/`
- **驗收條件**：
  - [ ] 不再載入 mock；所有請求打 `/api/v1/...` 真實端點，帶 session cookie（credentials 同源）
  - [ ] 登入成功回原頁；任何 401 導回登入，登入後回原頁；登出清除 session 並回登入頁；頁首顯示 `/me` 的 username
  - [ ] 403 顯示沒有權限且無重試；其他錯誤附 request_id
  - [ ] 本機起後端與前端後，可登入並查到一筆真實 Enrollment 的列表與詳情（回報附操作步驟與結果）
  - [ ] FE-01 的驗收測試與單元測試仍全數通過
- **驗證指令**：`make check`
- **回報檔**：`reports/INT-01.json`

## 執行資訊（task.py 產生）

- Issue：#22
- 分支：`feat/int-01`（從 develop 開出）
- 工作目錄：`.worktrees\INT-01`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1：qa B 自動化部分通過（make check：api 95、web 46；驗收測試未改；無 mock 殘留）。驗收條件 4（真實端到端）因 develop 上 BE-02 仍是 stub、後端無法啟動而未驗；待 BE-02 合併後，qa 以主目錄 develop 起後端、本分支起 web-dev 補做，再判定。
