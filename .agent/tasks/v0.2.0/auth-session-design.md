# 派工單 auth-session-design

- **類型**：docs
- **負責**：architect
- **目標**：更新 `auth.md` 與 DESIGN-03，補上 user-auth-api review 留下的契約缺口並納入 D-071
- **背景**：user-auth-api 已合併（PR #104）。review 結果 `reports/user-auth-api.review.md` 列了三件交給設計文件的事：限速原子化帶出的併發 429、上一輪未改的 `auth.md` §2 末條與 DESIGN-03 做法 #2 CSRF 寫法、D-071 三處修改與實作提示。管理員用 PATCH 重設自己的密碼時，照 D-071 字面一併失效自己的 session（manager 已決定照字面，寫進契約）
- **設計依據**：`docs/contracts/auth.md`、`docs/design/DESIGN-03.md`、`reports/user-auth-api.review.md`、`docs/decisions.md` D-071
- **相依**：無
- **負責檔案**：
  - `docs/contracts/auth.md`
  - `docs/design/DESIGN-03.md`
- **禁區**：程式碼、migration、其他契約；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] `auth.md` §3 補：同一 email 併發登入、未達鎖定時回 429（`Retry-After: 1`）
  - [ ] `auth.md` §2 末條、DESIGN-03 做法 #2 CSRF 寫法依 review 結果修正，與已合併的實作一致
  - [ ] D-071：`auth.md` §1、§3 `/auth/password` 註記、§4 PATCH 改為管理員重設密碼使該使用者所有 session 失效（含重設自己時自己的 session 也失效）；自己改密碼維持不登出其他裝置
  - [ ] DESIGN-03 派工順序加一張 D-071 後端實作單（task_id 用 `<功能>-<動作>`，如 `user-session-revoke`），寫明負責檔案、migration 編號（避開 BE-07 的 V7）、驗收目錄與相依
  - [ ] 「需調查」「待決定」分開列；沒有就寫無
- **驗證指令**：`make check`
- **回報檔**：`reports/auth-session-design.json`

## 執行資訊（task.py 產生）

- Issue：#105
- 分支：`docs/auth-session-design`（從 develop 開出）
- 工作目錄：`.worktrees\auth-session-design`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`
