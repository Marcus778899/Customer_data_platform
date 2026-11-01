# CLAUDE.md

## 專案規範（Customer Data Platform）

- 技術棧、目錄與測試慣例見 `docs/design/INIT-01.md`；開發指令入口是 `Makefile`（只支援 Git Bash／Linux，D-029）
- Makefile recipe 只用 ASCII：Windows GNU Make 3.81 以系統 codepage（cp950）轉碼，中文 echo 會亂碼；註解可寫中文
- 主目錄由 `git rev-parse --path-format=absolute --git-common-dir` 推出，`.env` 在主目錄、worktree 共用；Makefile 自動 export `.env.example` 列出的變數
- 前端用根目錄 `package.json` 的 npm workspaces，`node_modules` 在 checkout 根目錄，不在 `web/` 底下裝
- `api/mvnw` 要保持可執行（`git update-index --chmod=+x`），否則 CI permission denied；spotless 固定 `<lineEndings>UNIX</lineEndings>`，配合 `.gitattributes`
- 後端全部測試（含驗收，需 Docker）：`docker compose --env-file .env -f .worktrees/<task_id>/compose.yaml run --rm test`

<!-- agent-engineering:begin（由 ae 管理：ae adopt 加入、ae upgrade 更新；不要手改，專案規範寫在這個區塊外） -->
## Agent 團隊（agent-engineering）

所有 agent 啟動時都會讀這段。只放永遠成立的規則；情境知識放 skills。

@.agent/BOARD.md

- 布告欄（`.agent/BOARD.md`）上的「已定案決策」不可推翻；與之矛盾時停下來回報 manager
- 只有 manager 可以改布告欄（透過 `ae board`）、README.md、docs/decisions.md
- 每件工作都有派工單 `.agent/tasks/<task_id>.md`（task_id 是有意義的名稱，例 `todo-create-api`）；只在派工單的工作目錄 `.worktrees/<task_id>` 修改「負責檔案」；主目錄只寫自己角色規定的檔（回報與證據 `.agent/reports/`，uat、researcher、security 各自的 `docs/` 子目錄），其他一律不碰；過去階段的派工單封存在 `.agent/tasks/<tag>/`
- 分支：`main` ← `develop` ← `feat/`、`fix/`、`chore/`…；主目錄永遠停在 develop；不可直接 git push、merge、rebase、切換分支，一律透過 `ae`。本專案實際的分支名稱與文件目錄以 `ae config` 為準（這裡與角色檔寫的 develop、main、docs/ 是預設值）
- 需要甲方決定的事寫成 D-編號，集中在 `docs/decisions.md`；其他文件只引用編號
- 決策分級：只有這四類開 D-編號問甲方——①範圍（做不做、做多少）②成本與外部依賴（付費服務、新技術棧或套件）③品牌與對外語氣（品牌色、名稱、文案語氣）④不可逆或有風險（刪資料、權限、資料遷移、安全與法規；降低防護也算，例：資安掃描排除整個路徑、關掉規則）。資安掃描的誤判不算④：architect 可以只針對那一類誤判加窄規則，在忽略設定裡附理由，並證明同樣格式的真密鑰仍會被抓到，發版前由 security 逐條追認。其他可逆、在範圍內的細節（互動方式、版面、元件、欄位排序、命名…）由負責角色自己決定，寫進設計或 UI 規格的「自行決定」表，甲方核可設計時一起看
- 設定值（port、domain、token…）只放 `.env`；文件與程式只寫變數名稱；不可讀 `.env`
- 文件精簡：同一件事只寫一次，其他地方引用
- 「完成」必須附實際執行的驗證指令與輸出；沒跑過不算完成
- 回報格式依工具的 `contracts/report.schema.json`（`.claude/skills/agent-engineering/contracts/`；做派工單的角色都要寫）
- 甲方常態授權（甲方只做決策，其他由 manager 推進）：manager 可自行執行 `ae task`、`ae board`、`ae docs`、`ae check-env setup`、`ae release open`，以及 `ae pr` 的 `lock`、`open`、`record`、`ruling`、`sync`、`next`、`merge`（merge 由程式檢查 qa、review、CI 都通過才合併到 develop），以及 `gh issue create --label backlog`（minor 問題、medium／low 資安發現排進 backlog）；甲方關卡只有 `ae pr approve`、`ae pr unblock`、`ae release finish`，會跳確認視窗
- Bash 在 sandbox 裡；docker 只能直接下 `docker compose …`（唯一在 sandbox 外的例外），不要包在 make、script 裡或跟 `cd`、`&&` 寫在同一個指令
<!-- agent-engineering:end -->
