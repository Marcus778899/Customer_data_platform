APPROVE b436ca2

# Review due-backlog-perf（PR #141）

對照：派工單 `.agent/tasks/due-backlog-perf.md`、設計 `docs/design/due-backlog.md`（驗收方式的壓測目標表、風險表）、D-038／D-077／D-078。

## 檢查項目

| 項目 | 結果 |
|---|---|
| 變更範圍 | 只有 `docs/perf/v0.2.0.md`（純新增 37 行，原內容保留）與 `docs/perf/last-run.md`（重新產生）；禁區未動 |
| 目標表逐項 | 8 項都在，數字與 `last-run.md` 原始輸出一致；比值自行驗算：lockDue 0.092/0.064=1.44、claimDue 2.203/3.889=0.57、advance 58.7/53.2=1.10（門檻 0.7×53.2=37.2）、清空比 1702.9/187.9=9.06、relay 3232.6/2571.9=1.26；判定全部正確 |
| EXPLAIN 判讀 | 1 萬、10 萬的 `lockDue` 都是 `Index Scan using ix_enrollment_wake` rows=1、`claimDue` 子查詢是 `ix_effect_outbox_due` rows=100；無 Sort／Incremental Sort、無 temp；10 萬外層 UPDATE 改走 pkey Nested Loop 與設計原因表「表大時的成本選擇，不處理」一致 |
| D-078 只記錄項 | dispatch -3.3%／-2.1%、consumer -0.6%／0%，都在 20% 內，驗算正確；沒有調校 |
| 風險表 1（舊索引項目留在前端） | 判讀合理：t50 849.9 s 約是清空 1702.9 s 的一半（線性），10 萬平均 ≥ 1 萬，設計寫的偵測條件（≥0.7 倍）沒觸發；不調 autovacuum 符合設計對策 |
| 風險表 2（受每筆一交易 WAL 同步限制） | 判讀合理且誠實：不再是 O(N²)，但比設計推估「與派發同量級」低約 3.6 倍（210/58.7），報告寫約 3.5 倍；建議只列不開工，指向 DESIGN-03「每筆一個交易」與 D-078，manager 已開 #140 |
| 測試環境 | 沿用原節並寫明差異（後端由主目錄 develop `dde0044` 啟動，含 V7_2；啟動當下的環境變數未驗證，有揭露）；`dde0044` 只比本分支基底多 README，程式相同 |
| 資料清理 | 非壓測列數前後相同；qa 另查 `perf-%` 列為 0 |
| 寫死設定 | 只有變數名稱與 `.env.example` 預設值，沒有 secret |

## 非阻擋備註（不需改）

- 風險表 2 的等待事件（`WalSync` 619、`(running)` 2159）是同時段所有 active backend 的總和（advance、relay、consumer 同時在跑），不全歸 advance 迴圈；報告結論「單靠 WAL 同步不足以解釋」仍成立，#140 量時要在 `AdvanceLoop` 單筆交易內量，報告的建議已經是這樣寫。
- 「只記錄」表多列了 advance 1 萬 +61%；設計只要求 dispatch／consumer 超過 20% 要說明，多列無害。
- 本人設計對 advance 修正後吞吐的推估（與派發同量級）偏樂觀；這是設計的推估誤差，不是本單問題，已由 #140 追。

問題數：0
