APPROVE d12af9a

# Review demo-seed-integration（PR #133，sync develop 後重審）

HEAD d12af9a（headRefOid d12af9a9…）；base origin/develop = aba724f。

## 檢查範圍

| 項目 | 結果 |
|---|---|
| 變更檔案 vs 負責檔案 | `git diff origin/develop HEAD --stat`：只有 `.env.example`(+2)、`Makefile`(+7/-1)、`tools/demo-seed/seed.py`、`tools/demo-seed/verify.py`；都在派工單（含兩次追加）範圍內；`api/`、`web/`、`docs/contracts/`、`tools/perf/` 與 develop 相同 |
| 衝突標記 | f90a308 的 Makefile 有標記（兩處）；d12af9a 已移除。`git grep '^<<<<<<<\|^=======$\|^>>>>>>>' HEAD` 無結果 |
| `.PHONY`（Makefile:27） | `... demo-seed demo-verify perf`：同時保留本單的 `demo-verify` 與 develop 的 `perf` |
| targets | `demo-seed`、`demo-verify`（`python3 tools/demo-seed/verify.py`）、`perf`（`python3 tools/perf/run.py`，含 develop 原本兩行註解）三個都在、內容與兩邊原版一致，沒有重複或遺失 |
| `export CDP_DEMO_PASSWORD`（Makefile:16） | 獨立一行，不是 recipe 前綴，make 不會印出密碼；`ENV_VARS`／`NO_DOTENV` 未動 |
| ASCII recipe | `grep -nP '^\t.*[^\x00-\x7F]' Makefile` 無結果（非 ASCII 只在註解） |
| 與上次通過版本比對 | `git diff e5d48cb HEAD -- tools/demo-seed .env.example` 為空：seed.py、verify.py、`.env.example`（`# CDP_DEMO_PASSWORD=` 註解形式）與前次 review 通過的版本相同，前次 review 的阻擋項與三個小問題的修正都還在 |
| 設計符合度 | DESIGN-03 做法 #3（管理金鑰經 `POST /users` 建三角色）、批 5 INT-02 範圍不變；沒有寫死設定值 |
| 驗收條件 | 端到端證據沿用前次實跑（程式碼逐位元相同）；本輪 qa PASS 已說明未重跑即時環境，風險只在 develop 帶進的 api 索引變更，不改契約，可接受 |
| CI | Changes pass、Security pass；Check 審查時 pending（合併由 `ae pr merge` 檢查） |

## 問題

無阻擋項。

## 備註（不阻擋）

- `help` target 沒列 `demo-verify`（develop 的 `perf` 也沒列）；之後有人改 Makefile 時可一併補，不必為此重開。
