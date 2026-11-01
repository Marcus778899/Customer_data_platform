# 派工單 ci-speedup-design

- **類型**：docs
- **負責**：architect
- **目標**：調查 CI 為什麼慢、提出加速方案（只出分析與建議，不改 CI、Makefile、程式；實作另開單）
- **背景**：甲方 2026-10-31 詢問 CI 為何跑這麼久。manager 量到的數據（PR #110，run 41091268596）：Check job 約 6 分 50 秒，其中 `make check`（`mvnw spotless:check`＋web lint＋`mvnw verify` 含 Testcontainers 驗收測試）約 6 分 25 秒；Install dependencies 16 秒；Security job 約 45 秒（平行）。同一張 PR 較早的一次 run（41054164223）約 13 分鐘。另外 `ci.yml` 對 develop 的每次 push 都跑全套 CI，manager 的 `ae docs commit`（只改文件）、每次 `ae pr sync` 都會觸發，跟 PR 的 CI 搶 runner
- **設計依據**：`.github/workflows/ci.yml`、`Makefile`、`api/pom.xml`、`compose.yaml`、`docs/design/INIT-01.md`；`gh run list`／`gh run view <id> --json jobs`／`gh api .../actions/jobs/<id>/logs` 可查實際時間
- **相依**：無
- **負責檔案**：
  - `docs/design/ci-speedup.md`（新）
- **禁區**：`.github/`、`Makefile`、`api/`、`web/`、`compose.yaml` 一律不改；不可讀 .env；不可為了加速降低防護或驗證範圍而不標明（例：跳過驗收測試、覆蓋率、資安掃描）
- **驗收測試**：無（docs）
- **驗收條件**：
  - [ ] 以實際 CI 紀錄（至少 3 次 run）拆解時間花在哪裡（Maven 下載／編譯／單元測試／Testcontainers 啟動／各驗收測試類別／前端），附查詢指令與數字
  - [ ] 說明 13 分鐘那次為何比 7 分鐘慢（排隊、快取未命中或其他）
  - [ ] 列出加速方案，每項寫：預估節省時間、要改哪些檔、風險／是否降低驗證範圍、是否碰流程檔案或受保護路徑；例如 Maven／npm 快取、Testcontainers 容器重用、測試平行、拆 job 平行、文件類提交不觸發全套 CI（paths-ignore）等，只列適用的
  - [ ] 建議實作順序與拆單（task_id 命名照 `<功能>-<動作>`）；屬 CLAUDE.md 決策分級四類的另列「待決定」，其他寫進「自行決定」表
- **驗證指令**：附上用來量測的 `gh` 指令與輸出摘要
- **回報檔**：`.agent/reports/ci-speedup-design.json`

## 執行資訊（ae task 產生）

- Issue：#112
- 分支：`docs/ci-speedup-design`（從 develop 開出）
- 工作目錄：`.worktrees\ci-speedup-design`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
