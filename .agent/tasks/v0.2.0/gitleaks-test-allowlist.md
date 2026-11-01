# 派工單 gitleaks-test-allowlist

- **類型**：chore
- **負責**：architect
- **通道**：scan
- **目標**：讓 develop push 的 CI Security job 不再因 gitleaks 誤判驗收測試的虛構金鑰而失敗；只加最窄的忽略規則
- **背景**：調查報告 `.agent/reports/develop-gitleaks-finding.md`：commit f50659a 的 `api/src/test/java/com/cdp/acceptance/security/SecretToStringAcceptanceTest.java:32` 虛構值（`<小寫字>-SECRET-<4 英數>`）命中 gitleaks 預設 `generic-api-key`。PR run 只掃分支新 commit，develop push 掃整段歷史，所以只有 develop 紅；歷史不改寫。屬 CLAUDE.md 決策分級④的誤判例外，發版前由 security 逐條追認
- **設計依據**：`.agent/reports/develop-gitleaks-finding.md` 第 4 節的草案與反例；CLAUDE.md 決策分級④
- **相依**：無
- **負責檔案**：
  - `.gitleaks.toml`（新）
- **禁區**：不可排除整個路徑或整條規則；不可改 `.github/`、`api/`、流程檔案；不改寫 git 歷史；不可讀 .env
- **驗收測試**：無（scan；由 qa 驗證）
- **驗收條件**：
  - [ ] `.gitleaks.toml` 沿用預設規則（`useDefault = true`），allowlist 同時限定規則 `generic-api-key`、值 `^[a-z]+-SECRET-[0-9a-z]{4}$`、路徑 `^api/src/test/`，檔內附理由與調查報告路徑
  - [ ] 實際執行 gitleaks（與 CI 同版本、`--log-opts=HEAD` 掃整段歷史）在 worktree 得到 no leaks found，並確認被放過的 fingerprint 就是 f50659a 那一筆；附指令與輸出
  - [ ] 三個反例（亂數值、同格式放在 `src/main`、值多了尾巴）在暫存 repo 仍被抓到；附輸出
  - [ ] 若本機無法執行 gitleaks（例：被權限擋下），停下來回報，不要改用推論代替
- **驗證指令**：`gitleaks git --log-opts=HEAD`（版本與 CI 相同）
- **回報檔**：`.agent/reports/gitleaks-test-allowlist.json`

## 執行資訊（ae task 產生）

- Issue：#121
- 分支：`chore/gitleaks-test-allowlist`（從 develop 開出）
- 工作目錄：`.worktrees\gitleaks-test-allowlist`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
