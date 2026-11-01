# 派工單 relay-perf-bench

- **類型**：chore
- **負責**：backend
- **目標**：DESIGN-03 批 5 QA-06：relay 與推進迴圈壓測，產出 `make perf` 與壓測報告（D-038：不設流量目標，先量）
- **背景**：取代作廢的 relay-perf-test（負責角色開錯）。v0.2.0 已完成 relay／迴圈改寫（PR #99），壓測報告是第一版驗收項目。前一位 qa 的探勘事實附在檔尾追加，直接沿用、自行確認
- **設計依據**：`docs/design/DESIGN-03.md`「relay 與迴圈」表的「壓測（QA-06，D-038）」列；spec 07 §12「relay」；D-038、D-047
- **相依**：無
- **負責檔案**：
  - `Makefile`（只新增 `perf` target 與 `.PHONY`；recipe 只用 ASCII）
  - `tools/perf/`（新）
  - `docs/perf/`（新；報告 `docs/perf/v0.2.0.md`）
- **禁區**：`api/src/`、`web/`、`.github/` 不改；`perf` 不進 `make check`、不進 CI；不可讀 .env（make 會 export `.env.example` 列出的變數，腳本可印出名稱與值）；需要新增 `.env.example` 變數或程式掛鉤時先回報
- **驗收測試**：無（chore；由 qa 驗證）
- **驗收條件**：
  - [ ] `make perf` 一個指令可重跑（需要的容器以單獨一行 `docker compose …` 啟動的方式寫在報告裡）
  - [ ] 報告含 DESIGN-03「壓測」列全部項目：1 萬、10 萬筆同時到期的清空時間與各迴圈每秒筆數；子交易溢位指標；積壓下三個查詢的 `EXPLAIN (ANALYZE, BUFFERS)`；三張表壓測前後 `n_dead_tup` 與表／索引大小；confirm 延遲分布（註明代理值）
  - [ ] 報告寫明測試環境（`docker info` 的 CPU／記憶體、容器無資源限制、相依版本、相關變數名與值）與結論（瓶頸、是否需要後續單；只列建議不開工）
  - [ ] 壓測資料跑完後清掉（只刪 `perf-` 前綴的列），不影響既有開發資料
- **驗證指令**：`make perf`
- **回報檔**：`.agent/reports/relay-perf-bench.json`

## 執行資訊（ae task 產生）

- Issue：#128
- 分支：`chore/relay-perf-bench`（從 develop 開出）
- 工作目錄：`.worktrees\relay-perf-bench`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-10-31

## 前一位 qa（relay-perf-test）的探勘事實

- 本機沒有 host `psql`：用一個長駐的 `docker compose … exec -T postgres psql -X -q -A -t` 連線，語句間用 `\echo __END__` 分隔；PostgreSQL 18.6、superuser，`pg_stat_reset_slru` 可用
- `pg_stat_get_backend_subxact(pid)` 回傳欄位是 `subxact_count`、`subxact_overflowed`（不是 `subxact_overflow`），對自己的 backend 為 NULL
- 要 EXPLAIN 的查詢：`JdbcStateStore.lockDue`（limit 通常 1）、`JdbcOutboxStore.claimDue`（UPDATE … IN (select … for update skip locked) RETURNING，limit `OUTBOX_RELAY_BATCH_SIZE`）、`JdbcEventStore.lockUndispatched`（limit `ENGINE_BATCH_SIZE`）；都包在 BEGIN … ROLLBACK
- 造同時到期的積壓：先經 API 建兩個模板旅程（長等待→WAITING；PT2S→COMPLETED＋PUBLISHED outbox），再以 SQL `generate_series` 複製（id 前綴 `perf-<tag>-`、到期時間設同一未來時刻）；enrollment 要連 `node_trace` 一起複製；outbox 要有對應 enrollment；`delivery_task` 由 consumer 自建，不要複製；派發用不相符的事件名
- 吞吐與清空時間：事後以 `enrollment_record.updated_at`、`effect_outbox.published_at`、`delivery_task.updated_at`、`event.dispatched_at` 每秒分桶計算
- confirm 延遲：後端沒有指標掛鉤，DB 端代理值 `published_at - (available_at - OUTBOX_RELAY_LEASE)`；報告註明是代理值，並建議後續單加掛鉤（本單不改程式）
- 清空期間約每 0.25 秒取樣 `pg_stat_activity`：`SubtransSLRU`／`SubtransBuffer` 次數、`subxact_overflowed` 的 backend 數、其他 wait_event 分布
- 三張表壓測前 `VACUUM (ANALYZE)`，造資料後與清空後約 3 秒各取 `n_dead_tup`、`pg_table_size`、`pg_indexes_size`（讀前等約 2.5 秒讓統計刷新）
- 本機開發 DB 已有非壓測資料（約 9 個旅程、38 筆 enrollment），8888 已有一個來源不明的後端在跑（log 在 `%TEMP%/api.log`）：開跑前檢查並排除非壓測積壓，報告寫明用的是哪個後端 build

## 追加 2026-10-31

### .agent/reports/relay-perf-bench.review.md

REQUEST_CHANGES 71b0677

# Review relay-perf-bench (PR #129)

## 要修改

1. `docs/perf/v0.2.0.md:8` — 重跑步驟寫的是 `docker compose --env-file .env -f .worktrees/relay-perf-bench/docker-compose.yml up -d --wait`。這份報告合併後會一直留著，但 worktree 合併後就清掉，這行之後會找不到檔案，驗收條件 1「可重跑」在合併後就做不到。`docker-compose.yml` 有 `name: cdp`，在主目錄用和 worktree 用是同一個 project，所以只要改路徑 — 改成 `docker compose --env-file .env -f docker-compose.yml up -d --wait`（在主目錄執行，和 `make infra-up` 等價，跟 Makefile `perf` 註解寫的「needs make infra-up」一致）。

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 負責檔案 | diff 只有 `Makefile`（只加 `.PHONY` 的 `perf` 與 `perf` target，recipe 是 ASCII）、`tools/perf/pg.py`、`tools/perf/run.py`、`docs/perf/v0.2.0.md`、`docs/perf/last-run.md`；沒有改到 `api/src/`、`web/`、`.github/` |
| 不進 check／CI | `check: lint test` 沒變；有 `CI` 環境變數時 `run.py:622` 會拒絕執行 |
| EXPLAIN 的查詢有沒有照實作 | 對照 `JdbcStateStore.lockDue`、`JdbcOutboxStore.claimDue`、`JdbcEventStore.lockUndispatched`：WHERE、ORDER BY、`for update skip locked` 都一樣；limit 對照 `AdvanceLoop.java:83`、`EventDispatcher.java:98`（`failed.size()+1`，平常是 1）；claimDue 用 `OUTBOX_RELAY_BATCH_SIZE`。差別只有 lockDue 用 `select *`、claimDue 的 SET 寫死 `30 seconds`，兩者都在 rollback 的交易裡，不影響計畫 |
| DESIGN-03 壓測列的項目 | 清空時間／每秒筆數、`pg_stat_reset_slru`＋`pg_stat_slru`＋`SubtransSLRU` 取樣＋`subxact_overflowed`、三個 EXPLAIN、三張表的 `n_dead_tup`／大小、confirm 代理值（有註明是代理值），報告都有 |
| 清理只動 perf 資料 | `cleanup`／`cleanup_scenario`（`run.py:146-159`、`457-466`）每條 DELETE 都限定 `perf-` id 前綴、`perf-` 旅程名或 `perf\_` 事件名，而且包在一個交易裡；出錯或 `fail()`（SystemExit）時 `finally` 一樣會清；跑之前也會先清掉前一次中斷留下的 |
| SQL 拼接 | `lit()` 的來源只有程式內部的值（hex tag、DB 回傳的時間、整數化的 `PERF_SIZES`），沒有外部輸入；它靠 `standard_conforming_strings=on`，PG 18 預設就是 on。拼接可以接受 |
| 寫死設定 | 主機固定 `127.0.0.1`（反而避免不小心連到遠端 DB）；port、帳密、金鑰都從環境變數讀；`OUTBOX_RELAY_BATCH_SIZE`／`OUTBOX_RELAY_LEASE` 的備用預設值和 `.env.example` 一樣，而且 Makefile 一定會 export。沒有 secret 會進輸出或錯誤訊息（`RECORDED_VARS` 都不是機密） |
| 報告裡的瓶頸判斷 | 和 migration 的索引定義（只含第一欄）、`last-run.md` 的計畫（Index Scan 讀出全部同值列 → Incremental Sort）對得上；後續單只列建議，沒有開工 |

## 指定判斷的兩個做法

- **`pg.py`（只用 stdlib 自己寫的 PG wire client）：可以接受。** 不這樣做就要加 psycopg 這類新套件，那屬於決策分級 ②，要開 D-編號問甲方。它的範圍在 docstring 寫清楚了（只有 simple query、沒有參數、沒有 TLS），只連 loopback，大約 150 行。維護上的限制：(a) SCRAM 沒有驗 server-final 的簽章（`pg.py:99` 收到 code 12 就直接 pass），也接受 cleartext／md5，只連 loopback 時可以接受，但不可拿去連遠端；(b) 密碼沒有做 SASLprep，非 ASCII 密碼可能認證失敗。這兩點都不擋合併，建議在 docstring 補一句「只限 loopback，不驗伺服器簽章」。
- **`pg_read_file` 讀 `/proc`、cgroup：可以接受。** 讀的都是固定路徑，沒有外部輸入；讀失敗就退回 `unreadable`（`run.py:471-475`），不會讓整個流程失敗。它需要 superuser（`pg_stat_reset_slru` 也一樣），但用的是開發 compose 原本就有的 `POSTGRES_USER`，本單沒有增加權限；以後把開發帳號降權時，這裡只會少幾項環境資訊。在 Docker Desktop 上，容器看到的 `/proc/cpuinfo`、`meminfo` 就是 VM 的 CPU 和記憶體，和 `docker info` 的 NCPU、MemTotal 同一個來源。和派工單寫的 `docker info` 不一樣的地方，報告第 16 行有寫明。限制：cgroup 那兩項只代表 PostgreSQL 容器，RabbitMQ 容器有沒有限制沒驗證。不擋合併。

## 觀察（不擋合併）

- `cleanup` 也會刪「名稱是 `perf-` 開頭的旅程」和它所有的 enrollment，以及名稱是 `perf_` 開頭的事件，範圍比「id 前綴 `perf-`」大一點。報告第 11 行有寫明；開發者自己的資料不太可能這樣取名。
- 預設的 `PERF_OUT` 會覆寫 repo 裡的 `docs/perf/last-run.md`，檔頭有標「overwritten on every run」。
- 沒有跑任何測試或 `make perf`（照指示）；跑的部分以 qa 證據（`relay-perf-bench.qa.md`，PASS 71b0677）為準。
