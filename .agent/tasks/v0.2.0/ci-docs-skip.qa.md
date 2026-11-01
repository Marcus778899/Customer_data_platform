PASS 0c44074

# QA ci-docs-skip (PR #116)

範圍依 manager 指示：以 ci.yml 本身正確為準；Security（osv-scanner）與 Check（LoginSessionAcceptanceTest 順序問題）的失敗與本單無關，不計。

## 1. 判斷邏輯（獨立重跑，非引用回報）
把 ci.yml「Detect docs-only push」整段邏輯抄成函式，在 .worktrees/ci-docs-skip 對歷史 commit 實跑：

```
push bd85440..b164bcb code=false          (只改 .agent/BOARD.md、docs/decisions.md)
push d4a284a..bb0607b code=false          (多 commit，皆文件)
push 4dd7a44..b88b7e0 code=true           (改 api/)
push f6a908a..921c51d code=true           (混合)
push 0000...0000..HEAD code=true          (before 全 0)
push deadbeef...(不在歷史)..HEAD code=true (強推)
pull_request bd85440..b164bcb code=true   (PR 一律跑；同一組只改文件的 diff 在 PR 仍為 true)
push HEAD..HEAD code=true                 (diff 為空，照跑)
push bb0607b..HEAD code=true              (含 ci.yml)
```
白名單 regex 反例（grep -Ev 後仍被列出，即視為程式碼）：api/README.md、web/docs/a.md、.agentx/a、docs（無斜線）、README.md.bak、api/x 全部不在白名單，正確。
`--no-renames` 已在指令中，搬檔不會被誤判。

## 2. concurrency
group = pull_request 時 `ci-pr-<PR 號>`，其餘事件 `ci-run-<run_id>`（每個 push 自成一組）；cancel-in-progress 只在 pull_request 為 true。develop／main push 不會被取消。符合條件。

## 3. PR run 與 job 列表
Run https://github.com/Marcus778899/Customer_data_platform/actions/runs/41099319569（head 0c44074）
- Changes: completed / success（log：`event=pull_request before= after=2b8f4b06... code=true`）
- Check: 已啟動（in_progress，未被跳過）
- Security: 已啟動（in_progress）
三個 job 都有跑起來；YAML 有效（run 已被 GitHub 接受並建立 job；本機無 yaml 模組，未另行解析）。Check／Security 查驗當下尚未結束，其最終結果依指示不納入判定。`security` 無 needs、無 if，未被改動。

## 4. 其他
- 禁區：diff 只動 .github/workflows/ci.yml（+41 行），api/、web/、Makefile 未改；未使用 `**/*.md`。
- 回報有附改動差異說明與合併後確認方式（下一次 ae docs commit 的 run 應為 Changes success／Check skipped／Security success）。
- 殘餘風險（非 FAIL）：Changes 在含非 ASCII 檔名時 git 會加引號，不符白名單而照跑，屬安全方向。

## 問題數量：0
