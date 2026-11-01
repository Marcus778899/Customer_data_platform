PASS fc20857

## 驗收條件
- 驗收測試全綠：`npx vitest run tests/acceptance/journey-page-crash` -> 1 file, 24/24 passed（自行執行）
- 根因寫進回報：json open_questions 寫明 EndNode outputs=null（API 回應 versions/1），model.ts buildModel 的 Object.entries(n.outputs) 等處丟 TypeError。已核對 diff 與之相符
- 頁面層級錯誤邊界：App.tsx 的 PageBoundary 在 Shell 內，role=alert 顯示錯誤狀態，resetKey 隨網址變更重置（componentDidUpdate）。驗收測試涵蓋
- make check：`CONTAINER_TESTS=skip make check` -> 後端 77 tests 0 failures；web 19 files / 226 tests passed；"check passed"
- 其他 `.outputs`／`.config`／`.layout` 使用處：grep 只剩 model.ts（已用 outsOf）與 `def.layout?.nodes`（已防 null），無其他漏網

## 後端那次 surefire 失敗
重跑完整後端（含容器）：`docker compose --env-file .env -f .worktrees/journey-page-crash/compose.yaml run --rm test` -> Tests run: 397, Failures: 0, Errors: 0, BUILD SUCCESS。本單未改後端，判定為不穩定（flaky）或環境因素，與本單無關。未能重現，故不知是哪個測試；建議 manager 若再出現就開 backlog。PR CI 當下 Changes、Security pass，Check 仍 pending。

## 備註（非 FAIL）
- 後端對舊資料回傳 outputs/config=null，偏離 spec 03 §4／journeys.md，worker 已提請 manager 另開單
- 錯誤邊界的「重新載入」按鈕用 window.location.reload，規格文案未逐字比對（驗收測試已斷言 role=alert）
- 本次未啟動服務、未做瀏覽器實測（D-069）
