APPROVE a604dc4

# Review relay-perf-bench (PR #129)，退回後修正輪

## 上一輪退回項

| 項目 | 結果 |
|---|---|
| `docs/perf/v0.2.0.md:8` 重跑指令指向 worktree | 已修：改為 `docker compose --env-file .env -f docker-compose.yml up -d --wait`，第 7 行註明在主目錄執行。主目錄有 `docker-compose.yml`（`name: cdp`，與 `make infra-up` 同一個 project），合併後仍可重跑 |

## 檢查了什麼

| 項目 | 結果 |
|---|---|
| 這輪變更範圍 | `git diff --stat 71b0677 a604dc4`：只有 `docs/perf/v0.2.0.md`（4 增 2 刪）；`Makefile`、`tools/perf/pg.py`、`tools/perf/run.py`、`docs/perf/last-run.md` 與上一輪 review 過的版本相同，上一輪的檢查結果沿用 |
| 負責檔案 | PR 整體檔案清單仍是 `Makefile`、`tools/perf/pg.py`、`tools/perf/run.py`、`docs/perf/v0.2.0.md`、`docs/perf/last-run.md`，沒有新增檔案 |
| 重跑指令有沒有其他 worktree 路徑 | grep `worktrees`：只剩第 18 行，描述壓測當時受測後端的 build 來源，是歷史紀錄不是指令，可以接受 |
| 補上的 pg.py 限制說明 | 第 112 行寫明只連 127.0.0.1、不驗 SCRAM 伺服器簽章、不做 SASLprep、不可連遠端，和上一輪的建議一致 |
| 寫死設定、secret | 新增的文字沒有 port、帳密或網址的值，只寫檔名和變數名稱 |
| qa | `relay-perf-bench.qa.md`：PASS a604dc4，和這次 review 的 commit 相同 |

## 觀察（不擋合併）

- `docs/perf/v0.2.0.md:112-113` 新加的條目後面多一個空行，「限制」清單會變成 loose list，只影響排版。

沒有跑測試或 `make perf`（照指示）。
