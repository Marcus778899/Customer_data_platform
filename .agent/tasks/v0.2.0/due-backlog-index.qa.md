PASS 1134da0

## 驗證（PR #138，commit 1134da0）

- 後端全部測試（自己執行 `docker compose --env-file .env -f .worktrees/due-backlog-index/compose.yaml run --rm test`）：`Tests run: 397, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`
  - `DueQueueIndexAcceptanceTest` Tests run: 5, Failures: 0；`DueQueueUpgradeAcceptanceTest` Tests run: 1, Failures: 0
  - 既有驗收測試未改（`git diff 7ae2f15 HEAD --name-only` 只有 4 個負責檔案，`api/src/test` 無變動 = 與 lock/test commit 7ae2f15 相同）
- V7_2：`DROP INDEX` 後同名重建。欄位 `(next_wake_at, enrollment_id)`、`(available_at, idempotency_key)`，WHERE 條件與 V1／V2 原條件逐字相同；檔頭有 down（同名重建回單欄）；無 `CONCURRENTLY`（僅在註解說明不用）；沒有新增其他索引；檔名 V7_2，未佔 V8。與設計 #2 的 DDL 一致。
- `LOCK_DUE_SQL`、`CLAIM_DUE_SQL`：`git diff` 顯示字串逐字搬出，只是改成 public static final 常數，方法改用常數；ORDER BY、`FOR UPDATE SKIP LOCKED` 不變。
- spec 07：§4 索引表、§10 `effect_outbox` 索引欄、§8 台帳 V7_2 列、§12 V7_2 小節與 relay 搶列敘述，皆與 migration 一致。
- 只改負責檔案：4 個檔案皆在派工單負責清單內（另有 qa 的測試 commit）。
- CI：Check／Security 在驗證當下仍 pending，Changes pass；Security 若因 minimatch 失敗依指示不計入本單。

## 備註（非 FAIL）

- 設計寫 CONCURRENTLY 原則「寫進 spec 07 §8」，實際寫在 §12 V7_2 小節（§8 台帳列指向 §12）。內容一致，不影響結論。
- 未另跑 `make check`（lint）；compose test 已含全部單元與驗收。
