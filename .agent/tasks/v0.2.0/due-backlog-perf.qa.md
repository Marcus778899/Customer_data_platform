PASS b436ca2

# QA due-backlog-perf (PR #141)

範圍：壓測報告單，只改 docs/perf/v0.2.0.md、docs/perf/last-run.md（git diff --name-only 確認只有這兩檔；v0.2.0.md 純新增 37 行，原內容保留）。依 manager 指示不整套重跑 10 萬筆（約 1 小時），改以交叉核對加抽驗。

## 1. 數字交叉核對（v0.2.0.md「修正後對照」 vs last-run.md 原始輸出，修正前值對 v0.2.0.md 上方原表與設計目標表）

| 項目 | last-run.md 原始值 | 報告值 | 驗算 | 結果 |
|---|---|---|---|---|
| lockDue Execution Time 1萬／10萬 | 0.064／0.092 ms | 同 | 0.092/0.064 = 1.44 < 3 | 對、達標 |
| lockDue 計畫 | Limit > LockRows > Index Scan ix_enrollment_wake rows=1，無 Sort、無 Temp 行 | 同 | | 對、達標 |
| claimDue Execution Time | 3.889／2.203 ms | 同 | 0.57 倍 < 3 | 對、達標 |
| claimDue 計畫 | Index Scan ix_effect_outbox_due rows=100（limit 100），無 Sort／Temp；10 萬外層 UPDATE 走 effect_outbox_pkey Nested Loop | 同 | | 對、達標 |
| advance 10 萬 | 1702.9 s、timed out no、COMPLETED=100000 | 同 | 時限 3600 s 內 | 對、達標 |
| advance 平均 /s | 53.2（1萬）、58.7（10萬） | 同 | 58.7/53.2 = 1.10 >= 0.7（門檻 37.2） | 對、達標 |
| advance 清空時間比 | 187.9 s、1702.9 s | 同 | 9.06 <= 15 | 對、達標 |
| relay 平均 /s | 2571.9（1萬）、3232.6（10萬） | 同 | 1.26 >= 0.7 | 對、達標 |
| relay 10 萬清空 | 30.9 s | 30.9 s（修正前 131 s，對上原表） | | 對 |
| dispatch／consumer（D-078 只記錄） | 202.1／204.6；112.3／117.0 | 同；與修正前（209／209；113／117）差 -3.3%／-2.1%；-0.6%／0% | 皆在 20% 內 | 對 |
| advance 1 萬平均 | 53.2 vs 修正前 33 | +61%，已說明原因（lockDue 不再讀全部並列列） | 53.2/33 = 1.61 | 對；超過 20% 有在報告說明，符合設計目標表要求 |
| 判讀引用數字 | WalSync 619、(running) 2159、中位 64、尖峰 69、t50 849.9、t99 1686、子交易溢位 0、publish_count max=1、confirm p99 <= 0.025 | 同 | 210/58.7 = 3.6 倍（報告寫約 3.5） | 對 |

## 2. 驗收條件

- [x] 以 PERF_SIZES=10000,100000、PERF_TIMEOUT=3600 跑 make perf：last-run.md 含 n=10000 與 100000 兩組完整輸出，回報 exit 0；EXPLAIN 計畫時間戳 2026-11-01 與今日相符。docker 啟動方式回報檔有說明（PG／RabbitMQ 由 worker 起），本次未重跑，屬依賴回報之處。
- [x] 修正後對照逐項對上設計目標表（見上），每項標達標並附數字。
- [x] 未達標項目：無；風險表兩項（舊索引項目留前端、advance 受 WAL 同步）都有判讀；advance 約 58 筆/s 與設計推估 210 筆/s 差距已誠實記錄並只列建議；consumer／dispatch 照 D-078 只記錄。
- [x] 測試環境寫明（同 v0.2.0.md 原格式，並註明差異：後端由主目錄 develop dde0044 啟動；啟動當下環境變數未驗證，已揭露）。
- [x] 清理：last-run.md「Non-perf row counts」前後相同；我另查 DB：enrollment_record／effect_outbox 各 74 列、`perf-%` 列 0、journey_definition 與 event 的 `perf-%` 列 0。

## 3. 抽驗（PG 容器，指令 `docker compose --env-file .env -f docker-compose.yml exec -T postgres psql -U cdp -d cdp -c ...`）

- pg_indexes：`ix_enrollment_wake` = btree (next_wake_at, enrollment_id) WHERE status='WAITING' AND next_wake_at IS NOT NULL；`ix_effect_outbox_due` = btree (available_at, idempotency_key) WHERE status='PENDING'（V7_2 已套用）。
- 在未提交交易中塞 20000 筆同一 next_wake_at 的 WAITING 與 20000 筆同一 available_at 的 PENDING（`qa-` 前綴，連線結束自動回滾；事後查 `qa-%` 列 = 0），ANALYZE 後以程式內 LOCK_DUE_SQL（limit 1）與 CLAIM_DUE_SQL（limit 100）形式 EXPLAIN (ANALYZE, BUFFERS)：
  - lockDue：Limit > LockRows > Index Scan using ix_enrollment_wake，actual rows=1，Execution Time 0.057 ms，無 Sort、無 Temp。
  - claimDue：子查詢 Index Scan using ix_effect_outbox_due，actual rows=100，Execution Time 1.950 ms，無 Sort、無 Temp。
  結果與報告一致。

## 4. 其他檢查

- 寫死設定值：兩份文件只出現變數名稱與 .env.example 的預設值，無 token／密碼。
- 變更範圍：api/src、web、.github、Makefile、tools/perf 未動。
- 未跑 make check／單元測試：純文件單，不適用。

## 問題清單

無（0 項）。附註（非阻擋）：10 萬筆整套未由 QA 重跑，結論基於原始輸出交叉核對加索引／計畫抽驗；advance 吞吐與設計推估落差屬 D-078 範圍，由 manager 決定是否開單。
