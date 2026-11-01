APPROVE a089d9a

PR #136（head a089d9a42ce41dcf0834b1d3d834924ffa11c0fd），對照 `.agent/tasks/npm-minimatch-upgrade.md`、INIT-01、D-009。

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 負責檔案 | 只改 `package.json`（只加 `overrides`）、`package-lock.json`；`web/`、`.github/`、`Makefile`、osv 忽略設定都沒動 |
| 先升上游、沒有才用 override | `npm view`：eslint@latest 10.14.0 要求 `minimatch ^10.2.6`，@typescript-eslint/typescript-estree@latest 8.72.1 要求 `^10.2.2`，上游還沒有帶 11 的版本，所以用 override 符合派工單 |
| lockfile | minimatch 只有一份，10.2.6 → 11.0.3；新增間接相依 `@isaacs/nonrecursive-glob-match@1.0.3`（dev、BlueOak-1.0.0，授權跟 minimatch 一樣）；沒有其他變動 |
| 寫死設定、安全 | 沒有；只影響 dev 工具鏈，正式產物不含 minimatch |
| CI | Changes、Check、Security 都 pass（run 41173585579） |
| qa 證據 | 新舊兩邊都跑了 eslint，各檢查 49 個檔案，0 error；反例（debugger）仍會被抓到；vitest 196 個測試都過。這證明 glob 比對沒有因為換版而默默略過檔案 |

## override 跨 major 的風險：可以接受

我在 scratchpad 用 `npm pack` 抓 minimatch 10.2.6 和 11.0.3，比對 `dist/esm/index.d.ts`：11 移除了 `makeRe`（函式和方法）、`AST`、`debug`、`maxExtglobRecursion`、`regexp`、`matchOne`、`slashSplit`。接著 grep 上游實際怎麼呼叫 minimatch：

| 上游（lock 版本） | 用到的 API | 11 裡還有嗎 |
|---|---|---|
| eslint 10.13.0 `lib/eslint/eslint-helpers.js` | `new Minimatch(p, {dot:true})`、`.match(path)`、`.match(path, true)`、`.pattern` | 有 |
| @eslint/config-array 0.23.5 | `new Minimatch(p, {dot:true,...})`、`.match()` | 有 |
| typescript-estree 8.71.2 `useProgramFromProjectService.js` | `minimatch(file, pattern, {dot:true})` | 有 |

上游沒用到任何被移除的 API。`exports` 和 `engines`（node 18／20／>=22，本專案是 >=24）兩版一樣。剩下的風險是 match 的語意可能有細微差異，這點 qa 的 49 檔對照已經涵蓋。影響範圍只在 lint：最壞情況是 lint 少檢查到某些檔，不會影響正式產物。

## 要不要記下「上游跟上後拿掉 override」：要，開 backlog issue，不寫 INIT-01

- JSON 不能加註解，`package.json` 裡無法標示這是暫時的；不記的話，日後不會有人知道可以拿掉
- INIT-01 記的是長期的技術棧與慣例。這個 override 只是過渡狀態，寫進去要另外開一張設計 PR，之後還要再改一次，不划算
- 建議 manager 用 `gh issue create --label backlog` 開一張，內容：「eslint／@typescript-eslint/typescript-estree 的 minimatch 範圍放寬到 >=11.0.1 後，移除根目錄 `package.json` 的 `overrides.minimatch`，重新產生 lockfile，`npm ls minimatch` 確認版本」。觸發條件：日後升級 eslint／typescript-eslint 時一併檢查
- 這不是退回的理由，所以判 APPROVE

## 其他（不影響結論）

- 合併後主目錄和各 worktree 共用的 `node_modules` 還是 10.2.6，要在主目錄跑一次 `npm ci`，本機 lint 才會跟 CI 一致（worker 的 open_questions 已提出）
