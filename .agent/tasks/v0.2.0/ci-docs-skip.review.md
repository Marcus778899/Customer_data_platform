APPROVE 0c44074

# Review ci-docs-skip（PR #116）

範圍：依 manager 指示只看 `.github/workflows/ci.yml` 的改動。Check（LoginSessionAcceptanceTest 順序問題，修正在 user-session-revoke）與 Security（osv-scanner，交給 deps-security-upgrade）的失敗是既有問題，不計。

## 檢查項目

| 項目 | 依據 | 結果 |
|---|---|---|
| 只改負責檔案 | 派工單「負責檔案」 | 通過：PR 只改 `.github/workflows/ci.yml`（+41／-0）；`api/`、`web/`、`Makefile` 沒動 |
| `security` job 不跳過、條件沒改 | 禁區；設計「威脅與對策」密鑰掃描那一列 | 通過：`security` 沒有 `needs`、沒有 `if`，diff 也沒碰到它 |
| PR 一律跑全套 | 禁區；設計 #1 | 通過：`EVENT != push` 時 `code=true`；qa 的 run 41099319569 記到 `event=pull_request ... code=true`，Check 有啟動 |
| 白名單 | 禁區（`docs/**`、`.agent/**`、根目錄 `README.md`、`BOARD.md`，不用 `**/*.md`） | 通過：`grep -Ev '^(docs/\|\.agent/\|README\.md$\|BOARD\.md$)'` 有 `^` 錨定，只放行根目錄的 README.md、BOARD.md；qa 的反例（`api/README.md`、`web/docs/a.md`、`.agentx/a`、`README.md.bak`）都被當成程式碼 |
| `before` 全 0、強推照跑 | 驗收條件 1；設計 #1 | 通過：有比對全 0；`git cat-file -e "$BEFORE^{commit}"` 失敗（強推後舊 sha 不在歷史裡）時照跑；diff 失敗或為空也照跑。條件不成立時一律 `code=true`（fail closed） |
| 搬檔 | 設計「威脅與對策」#1（白名單寫太寬） | 通過：`--no-renames` 會同時列出舊路徑和新路徑，從 `api/` 搬到 `docs/` 不會被當成只改文件 |
| 指令注入 | 安全 | 通過：`github.event.before`、`github.sha` 經 `env:` 傳入，沒有直接用 `${{ }}` 內插進 script；Changes 的 checkout 設了 `persist-credentials: false`；workflow 的 `permissions: contents: read` 沒變 |
| `concurrency` 只作用在 PR | 驗收條件 2；設計 #4 | 通過：PR 的 group 是 `ci-pr-<PR 號>`，`cancel-in-progress` 只在 `pull_request` 時為 true；push 的 group 帶 `run_id`，每個 run 自成一組，develop／main 的 push 不會被取消或排隊 |
| 合併閘門相容 | 合併閘門 `check_ci`（`.claude/skills/agent-engineering/lib/agent_engineering/pr.py:543`，用 `gh pr checks`） | 通過：PR 不會跳過 Check，所以設計「需調查」提到的「被跳過的 job 的結束碼」不影響閘門；被取消的是舊 commit 的 run，`gh pr checks` 只看最新 commit |
| `check` 被跳過時 run 的結論 | 設計 #1 | 通過：Changes 失敗時 Check 會被跳過，但整個 run 會因為 Changes 失敗而顯示失敗，不會被誤當成通過 |
| 寫死設定值 | 規範 §5 | 無：只有 git 的全 0 sha 常數 |
| 測試是否測到驗收條件 | qa 證據 `.agent/reports/ci-docs-skip.qa.md` | 通過：qa 把判斷邏輯抄出來，對歷史 commit 實際跑過（只改文件、改到 api、混合、全 0、強推、diff 為空、PR），也附了 run 連結和 job 列表 |

## 備註（不影響結論）

- Changes job 在 PR 上也會做 `fetch-depth: 0` 的 checkout，多花幾秒，不影響正確性。
- 檔名含非 ASCII 字元時 git 會加引號，比對不到白名單就照跑，屬於安全的方向（qa 也有記錄）。
- 合併後要確認：下一次 docs commit 的 develop run 應該是 Changes success、Check skipped、Security 照跑。

問題數量：0
