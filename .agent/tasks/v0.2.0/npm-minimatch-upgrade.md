# 派工單 npm-minimatch-upgrade

- **類型**：chore
- **負責**：frontend
- **目標**：讓 CI 的 Security job（osv-scanner）不再因 npm `minimatch` 漏洞失敗：升到修正版
- **背景**：PR #129 的 CI（run 41172886849，Security job 124454458103）osv-scanner 報 GHSA-g3hv-75wg-m249（分數 6.9）：`minimatch` 10.2.6（dev 相依），修正版 11.0.1，在 `package-lock.json`。`npm ls minimatch`：由 `eslint@10.13.0`、`@eslint/config-array`、`typescript-eslint@8.71.2` 的 `typescript-estree` 帶入。新公布漏洞，擋住所有 PR 合併；升版屬 D-009 團隊安排
- **設計依據**：`docs/design/INIT-01.md`（前端工具鏈、npm workspaces 在根目錄）；D-009
- **相依**：無
- **負責檔案**：
  - `package.json`（根目錄；只限 `overrides` 或升級直接相依的版本）、`package-lock.json`
  - `web/package.json`（只限 eslint／typescript-eslint 版本，若升上游即可解決）
- **禁區**：`web/src/`、`web/tests/`、`api/`、`.github/`、`Makefile` 不改；不可用 osv-scanner 忽略設定讓掃描通過；不可讀 .env
- **驗收測試**：無（chore；既有測試全部要過）
- **驗收條件**：
  - [ ] 優先升上游（eslint／typescript-eslint）到已帶入修正版 minimatch 的版本；上游沒有時才用根目錄 `overrides` 強制 `minimatch` ≥ 11.0.1，並確認 eslint 與 typescript-eslint 與 minimatch 11 相容
  - [ ] `npm ls minimatch` 全部 ≥ 11.0.1（附輸出）；`npm ci` 乾淨安裝成功
  - [ ] `make lint`（eslint＋tsc）與 `make check` 通過，附輸出摘要；lint 結果與升版前相同（沒有因版本變化而失效或新增大量錯誤）
  - [ ] PR 的 CI Security job 綠燈
- **驗證指令**：`npm ls minimatch`；`npm ci`；`make check`
- **回報檔**：`.agent/reports/npm-minimatch-upgrade.json`

## 執行資訊（ae task 產生）

- Issue：#134
- 分支：`chore/npm-minimatch-upgrade`（從 develop 開出）
- 工作目錄：`.worktrees\npm-minimatch-upgrade`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
