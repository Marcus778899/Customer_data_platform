PASS 6e55423

# QA user-session-revoke（PR #110，sync 後重驗）

- HEAD：6e55423（與 PR headRefOid 6e554233e0a8 一致），worktree 無未提交變更
- 指令：`docker compose --env-file .env -f .worktrees/user-session-revoke/compose.yaml run --rm test`
- 結果（原始輸出摘要）：
  - `Tests run: 311, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（exit 0）
  - `SessionRevokeAcceptanceTest`：Tests run: 13, Failures: 0, Errors: 0（驗收條件 1–4）
  - `LoginSessionAcceptanceTest`：Tests run: 19, Failures: 0, Errors: 0（既有驗收，含順序修正，條件 5）
  - 第一次執行即通過，無容器環境錯誤，不需重跑
- CI（`gh pr checks 110`，run 41110992206）：Check pass（6m30s）、Security pass（46s）
- 寫死設定值檢查：user／security 主程式碼只出現環境變數名稱，無 port／網址／token
- 驗收條件對照：管理員重設他人密碼 session 失效、重設自己密碼仍 200 後自身失效、僅改其他欄位或錯誤時不影響、`/auth/password` 不登出其他裝置、既有驗收全綠，皆由上述測試涵蓋並通過
- 備註：合併須排在 profile-search-api 之後（V7_1 在 V7 之後），派工單已載明
