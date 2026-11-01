TEST_WRONG 5937038

驗收測試 lock 檢查：跟 lock（commit 5937038）時一樣，沒被改過

依據：`docs/contracts/auth.md` §4 PATCH（`409` `LAST_ADMIN`：會使啟用中的 `ADMIN` 變成 0 位）與 §4 範例（唯一啟用中的 `ADMIN` 降為 `VIEWER` → `409`）；CI run 41054164223 兩次執行（job 124057360904、124060408623）皆在同一斷言失敗，同一份程式碼（8ab85dd）在 run 40068392398 通過。

## 判定

| 測試名稱 | 契約條文 | 判定與理由 |
|---|---|---|
| `acceptance/user-auth-api/LoginSessionAcceptanceTest::role_change_takes_effect_on_next_request` | auth.md §4 PATCH `409 LAST_ADMIN` | 測試錯。測試把新使用者升為 ADMIN 再降回 VIEWER，並斷言降級回 `200`；依契約只有在另有啟用中的 ADMIN 時才成立。系統沒有預設 ADMIN 使用者，是否另有 ADMIN 取決於其他測試（`LastAdminAcceptanceTest`、`SessionRevokeAcceptanceTest` 等會建立、降級、停用 ADMIN）先執行並留下的狀態，測試類別執行順序一變，該使用者就是唯一的 ADMIN，實作依契約正確回 `409`。與本單實作無關 |

## 測試應該怎麼改（qa）

1. `role_change_takes_effect_on_next_request`：開頭先用 `AuthHttp.createUserId(port, uniqueEmail(), "ADMIN")` 建一位測試自己持有的啟用中 ADMIN，讓降級不受其他測試留下的狀態影響；其餘斷言不變
