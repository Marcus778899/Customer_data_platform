PASS a604dc4

# QA relay-perf-bench (PR #129), review 退回後修正輪

上一輪 PASS 71b0677（1 萬筆重跑）的結論保留；本輪只確認 a604dc4 的差異。

- `git diff --stat 71b0677 HEAD`：只有 docs/perf/v0.2.0.md（4 增 2 刪），程式、Makefile、tools/ 與上一輪相同。
- 重跑方式第 1 步改為主目錄 `docker compose --env-file .env -f docker-compose.yml up -d --wait`；主目錄存在 docker-compose.yml，`docker compose --env-file .env -f docker-compose.yml config -q` 無錯誤輸出（指令可解析）。
- 重跑指令區不再引用 `.worktrees/`；全檔唯一的 `.worktrees/` 字樣在第 18 行，是描述壓測當時受測後端 build 的歷史事實，不是指令。
- 限制段已補 pg.py 說明（只連 127.0.0.1、不驗 SCRAM 伺服器簽章、不做 SASLprep、不可連遠端）。
- 未重跑 make perf 與含容器測試（派工指示）。

問題數量：0
