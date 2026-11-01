PASS 5595b29

# QA ci-surefire-fork (PR #120)

## 改動範圍
`git diff develop...HEAD --stat`：只改 `api/pom.xml`（+9 行，maven-surefire-plugin `forkCount=2`、`reuseForks=true`，頂層 build）。未碰 `api/src/`、`.github/`、Makefile、compose.yaml；無排除／跳過測試。專案無 failsafe。

## 本機（自己執行）
`docker compose --env-file .env -f .worktrees/ci-surefire-fork/compose.yaml run --rm test`
```
[INFO] Tests run: 391, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  05:22 min
```
log 內的 APPLICATION FAILED TO START 為啟動驗證測試的預期輸出，不影響結果。

## 測試數量
- 本 PR 只改 pom，未動測試檔；CI 3 次皆 `Tests run: 391`（run 3 日誌確認），本機 391，一致。
- 改動前數字：回報稱 stash 基準 391；develop 最近的 CI 因 Security 失敗而跳過 Check，無法取得改動前 CI 數字（設計文件的 311 是更早的量測，之後 profile-search-api 合併而增加）。以「僅改 pom」佐證數量不變。

## CI 連續 3 次（同一 run 41117703989，rerun 兩次，每次待前一次完成）
Run：https://github.com/Marcus778899/Customer_data_platform/actions/runs/41117703989

| 次 | Check 結論 | Check 時間 | Security | 
|---|---|---|---|
| attempt 1 | success | 19:44:41 -> 19:50:37 = 356 秒 | success |
| attempt 2 | success | 19:51:14 -> 19:56:36 = 322 秒 | success |
| attempt 3 | success | 19:57:10 -> 20:02:35 = 325 秒 | success |

改動前（設計文件量測表）：403 秒（run 41088821210）、413 秒（run 40068852878）。改動後平均約 334 秒，縮短約 70–90 秒（約 17–20%），小於做法 #2 預估的 150 秒；surefire 以外的步驟（建置、映像）時間不變，且 CI 日誌時間有變動，屬 runner 變異範圍。三次皆全綠，無偶發失敗，故未需 `-DforkCount=1` 對照。

## 問題
無（0）。備註：節省幅度低於預估（約 2.5 分鐘 vs 實測約 1–1.5 分鐘），建議 manager 在設計文件量測表註記。
