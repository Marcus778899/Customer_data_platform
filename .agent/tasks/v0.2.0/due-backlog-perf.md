# 派工單 due-backlog-perf

- **類型**：chore
- **負責**：backend
- **目標**：D-077：due-backlog-index（PR #138）修正後重跑 relay 與推進迴圈壓測，證明大量同時到期不再是 O(N²)，報告加「修正後對照」
- **背景**：relay-perf-bench（PR #129）壓測：1 萬筆清空 300 秒、10 萬筆 1 小時只清 18,944 筆；設計 `docs/design/due-backlog.md`（PR #131）以補索引第二欄修正，已由 due-backlog-index 合併（V7_2）。D-078：派發與送達吞吐調校不在本版範圍，報告只記錄、不調
- **設計依據**：`docs/design/due-backlog.md`「驗收方式」的壓測重跑對照目標表、「風險」表；D-038（不設流量目標）、D-077、D-078
- **相依**：due-backlog-index（PR #138）、relay-perf-bench（PR #129），皆已合併
- **負責檔案**：
  - `docs/perf/v0.2.0.md`（加「修正後對照」節；原內容保留）
  - `docs/perf/last-run.md`（重新產生）
- **禁區**：`api/src/`、`web/`、`.github/`、`Makefile`、`tools/perf/` 不改（設計註明 SQL 未變，EXPLAIN 不需調整；若腳本確實要改才能量，先停下回報）；不可讀 .env
- **驗收測試**：無（chore；由 qa 驗證）
- **驗收條件**：
  - [ ] 以 `PERF_SIZES=10000,100000`、`PERF_TIMEOUT=3600` 跑 `make perf`，需要的容器以單獨一行 `docker compose …` 啟動
  - [ ] 「修正後對照」逐項對上設計目標表：`lockDue`、`claimDue` EXPLAIN 無排序節點、Index Scan 列數 ≤ limit、無 temp、10 萬 Execution Time < 1 萬的 3 倍；advance 10 萬在時限內清空、平均筆/s（10 萬）≥ 0.7 ×（1 萬）、清空時間比 ≤ 15；每項標達標／未達標並附數字
  - [ ] 未達標項目照設計「風險」表寫出判讀與建議（只列建議不開工）；consumer／派發吞吐照 D-078 只記錄
  - [ ] 報告寫明測試環境（同 v0.2.0.md 原格式）；壓測資料跑完後清掉（只刪 `perf-` 前綴的列）
- **驗證指令**：`PERF_SIZES=10000,100000 PERF_TIMEOUT=3600 make perf`
- **回報檔**：`.agent/reports/due-backlog-perf.json`

## 執行資訊（ae task 產生）

- Issue：#139
- 分支：`chore/due-backlog-perf`（從 develop 開出）
- 工作目錄：`.worktrees\due-backlog-perf`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
