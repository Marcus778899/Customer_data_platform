# 派工單 relay-perf-test

- **類型**：chore
- **負責**：qa
- **目標**：DESIGN-03 批 5 QA-06：relay 與推進迴圈壓測，產出 `make perf` 與壓測報告（D-038：不設流量目標，先量）
- **背景**：v0.2.0 已完成 relay／迴圈改寫（relay-loop-rewrite，PR #99）；壓測是第一版驗收項目之一（DESIGN-03「驗收：A 第一版」的「壓測報告」）
- **設計依據**：`docs/design/DESIGN-03.md`「relay 與迴圈」表的「壓測（QA-06，D-038）」列（量測項目完整清單）；spec 07 §12「relay」；D-038、D-047
- **相依**：無
- **負責檔案**：
  - `Makefile`（只新增 `perf` target；recipe 只用 ASCII）
  - `tools/perf/`（新；壓測腳本與資料產生）
  - `docs/perf/`（新；壓測報告 `docs/perf/v0.2.0.md`）
- **禁區**：`api/src/`、`web/`、`.github/` 不改；`perf` 不進 `make check`、不進 CI；設定值只放 `.env`／文件只寫變數名；不可讀 .env；需要新增 `.env.example` 變數或程式掛鉤時先回報，不自行改
- **驗收測試**：無（chore；由另一位 qa 驗證）
- **驗收條件**：
  - [ ] `make perf` 一個指令可重跑（需要的容器以單獨一行 `docker compose …` 啟動的方式寫在報告裡）
  - [ ] 報告含 DESIGN-03「壓測」列的全部項目：1 萬、10 萬筆同時到期的清空時間與各迴圈每秒筆數；子交易溢位（`pg_stat_slru` 的 subtransaction `blks_read`／`blks_zeroed` 增量、`SubtransSLRU` 等待次數、`subxact_overflow`）；積壓下 `lockDue`、`claimDue`、派發佇列的 `EXPLAIN (ANALYZE, BUFFERS)`；`effect_outbox`、`event`、`enrollment_record` 壓測前後 `n_dead_tup` 與表／索引大小；confirm 延遲分布
  - [ ] 報告寫明測試環境（機器規格、容器資源限制、相依版本、相關 `.env` 變數名與值的來源）與結論（瓶頸在哪、是否需要後續單；需要的話列建議，不自行開工）
- **驗證指令**：`make perf`
- **回報檔**：`.agent/reports/relay-perf-test.json`

## 執行資訊（ae task 產生）

- Issue：#126
- 分支：`chore/relay-perf-test`（從 develop 開出）
- 工作目錄：`.worktrees\relay-perf-test`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
