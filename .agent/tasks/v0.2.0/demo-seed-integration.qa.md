PASS d12af9a

# QA demo-seed-integration（PR #133，sync develop 後重驗）

本輪範圍：develop（due-backlog-design／due-backlog-index／perf）合併進來後的狀態，Makefile 衝突解法是否正確。

## 1. 合併狀態
- HEAD d12af9a = f90a308（合併，Makefile 含 4 行衝突標記）+ d12af9a（移除標記）。
- `git grep -n '^<<<<<<<\|^>>>>>>>' HEAD`：HEAD 無任何衝突標記（f90a308 的 Makefile 有 4 行，確認 worker 自述屬實，但最終狀態乾淨）。
- `git diff develop --stat`：只有 `.env.example`(+2)、`Makefile`(+7/-1)、`tools/demo-seed/seed.py`、`tools/demo-seed/verify.py`，都在負責檔案內；`api/`、`web/`、`docs/contracts/`、`tools/perf/` 與 develop 無差異。
- `git diff e5d48cb HEAD -- tools/demo-seed .env.example`：空（前次 qa PASS／review 修正後的 seed.py、verify.py、.env.example 與本次完全相同）。

## 2. Makefile
`git diff develop -- Makefile`：只加 `export CDP_DEMO_PASSWORD`（含註解）、`.PHONY` 補 `demo-verify`、`demo-verify` target；`perf` target 與 `.PHONY` 內的 `perf` 保留。recipe 為 ASCII。

`make -n demo-seed demo-verify perf`：
```
python3 tools/demo-seed/seed.py
python3 tools/demo-seed/verify.py
python3 tools/perf/run.py
process_begin: CreateProcess(NULL, "", ...) failed.
```
`process_begin` 警告：在主目錄（develop，未含本 PR）跑 `make -n perf` 同樣出現，所以不是本 PR 造成（Windows GNU Make 3.81 對 `$(shell ...)` 的既有現象）。
`python3 -m py_compile` seed.py、verify.py、tools/perf/run.py：皆 ok。

## 3. make check（自己跑）
exit 0：後端 `Tests run: 77, Failures: 0, Errors: 0`（BUILD SUCCESS）；前端 `Test Files 16 passed (16)`、`Tests 196 passed (196)`；結尾 `check passed`。

## 4. 這張單原本驗收（實跑部分）
本輪驗證環境沒有後端與 PostgreSQL（localhost:8888 無回應、5432 連不上；`make infra-up` 受 docker 規則限制不能包在 make 內啟動），所以我沒有重跑 `make demo-seed`／`make demo-verify` 的即時端到端。判斷依據：seed.py、verify.py、.env.example 與前次已實跑並 PASS／review 通過的版本逐位元相同，本輪唯一變動是 Makefile 合併，已在第 2 項靜態確認 target 正確。風險說明：develop 帶進的 due-backlog-index（api/ 索引變更）理論上不影響 API 契約，但未在即時環境重驗；建議 uat 階段收尾時的整體實跑涵蓋。

## 5. 其他
- 寫死設定：新增內容無 port／網址／token；密碼只用變數名 `CDP_DEMO_PASSWORD`。
- `.env.example` 為註解形式 `# CDP_DEMO_PASSWORD=`，不會被當必填。

問題數量：0（第 4 項為驗證範圍的限制，非缺陷）
