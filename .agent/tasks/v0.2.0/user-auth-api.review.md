APPROVE b88b7e0

# user-auth-api code review 第 2 輪（PR #104，HEAD b88b7e0）

對照：派工單（範圍以「範圍修正」為準）、`docs/contracts/auth.md`、上一輪 review（4dd7a44）、worker 回報、qa 證據（PASS b88b7e0，297 測試）。用 `gh pr diff 104` 看全部變更，用 `git show b88b7e0` 看本輪修正。上一輪第 1 輪已逐項檢查過的部分（session 固定攻擊、每請求重載、Bearer 優先、CSRF、角色矩陣、LAST_ADMIN、SQL、設定、log 遮罩）在 b88b7e0 沒有變動，維持通過。

## 上一輪問題的處理

| # | 上一輪問題 | 修正 | 結果 |
|---|---|---|---|
| 1 | 密碼過長時用時間差能判斷帳號是否存在 | `PasswordHasher.java:35-41`：密碼過長或 hash 為 null 時也對 dummy hash 跑一次 `encoder.matches`；`matchesDummy` 遇到過長密碼改用 "x"（`:44-48`）。encoder 改成可注入；`PasswordPolicyTest.over_long_password_costs_one_bcrypt_comparison_on_every_path` 驗證 5 條路徑（存在／不存在 × 過長／正常、hash null）都剛好跑一次比對 | 已處理 |
| 2 | 並行請求可以繞過限速 | `LoginRateLimiter.tryBegin/finish`：在同一個 `compute` 裡原子地判斷「有效失敗數 + 進行中數 ≥ 門檻」並佔用名額；`finish` 依 SUCCESS／FAILURE／ABORTED 更新。`AuthController.java:86-103` 用 try/finally 確保名額一定會釋放（DB 例外記成 ABORTED，不計數）。不變式「失敗數 + 進行中數 ≤ 門檻」成立，所以 `finish(FAILURE)` 不會讓失敗數超過門檻，「鎖定中不延長」仍成立。`concurrent_wrong_passwords_never_admit_more_than_the_threshold`（40 執行緒、門檻 3）驗證只有 3 個請求進得去 | 已處理 |
| 建議 | email 沒有長度上限 | `AuthController.java:73-76`：超過 254 字元回 `400 VALIDATION_FAILED`，不查使用者也不進限速表；有單元測試 | 已處理 |
| 建議 | 登出時 `CDP_SESSION` 的 secure 寫死 | `AuthController.java:130` 改成跟 `server.servlet.session.cookie.secure`，和 `SecurityConfig.java:57-60` 的 XSRF cookie 同一個來源 | 已處理 |

## 本輪變更的其他檢查

| 項目 | 結果 |
|---|---|
| 負責檔案 | b88b7e0 只改了 `security/`、`user/` 與對應的單元測試；驗收測試目錄從 f50659a 之後沒有變動（`git diff 4dd7a44 b88b7e0 -- api/src/test/java/com/cdp/acceptance` 是空的） |
| 限速計時 | 鎖定剩餘時間 = 最後一次失敗 + lockout；超過 lockout 的失敗由 `liveFailures` 歸零；成功時清空；`finish` 在失敗數與進行中數都是 0 時會刪掉該筆，表不會無限長大 |
| 寫死的值 | 沒有新增 |

## 不擋合併的觀察（併進設計文件更新單）

| 位置 | 觀察 | 建議 |
|---|---|---|
| `LoginRateLimiter.java:73-77` 對照 `auth.md` §3「超限回應」 | 名額被進行中的嘗試佔滿時（還沒鎖定），回 `429`、`Retry-After: 1`。契約只寫「鎖定中」才回 429。這是上一輪 review 要求的做法，只有同一個 email 同時有 ≥ 門檻數個請求時才會發生，屬於合理的延伸 | 在 `auth.md` §3 的「超限回應」列加一句：「同一個 email 同時進行中的嘗試加上失敗計數達到門檻時，也回 `429`（`Retry-After` 至少 1）」 |
| 上一輪「需要改的設計文件」 | `auth.md` §2 最後一條（ADMIN 打未列路徑回 404／405）、`DESIGN-03.md` 做法 #2 的 CSRF 寫法 | 還沒改，照上一輪的寫法併入同一張設計文件更新單 |

## 給下一張後端單：配合 D-071 要怎麼改 `auth.md`（本次不改）

| 位置 | 現行 | 改成 |
|---|---|---|
| §4 PATCH `/api/v1/users/{user_id}` 表格下方 | 沒有寫 | 新增一條：「請求帶 `password` 時，該使用者所有現有 session 立即失效，下一個請求回 `401` `UNAUTHENTICATED`（D-071）；管理金鑰呼叫時也一樣。只改 `display_name`／`role`／`status` 時 session 不失效（停用與改角色照 §1 在下一個請求生效）」 |
| §3 `POST /auth/password` 的「成功」欄 | `204`；其他 session 不登出 | 維持不變，在後面加註「（與 §4 管理員重設不同，見 D-071）」，避免被誤讀成兩者一致 |
| §1 最後一條（session 每個請求都以當下使用者資料判斷） | 停用、改角色 | 加上「管理員重設密碼 → 下一個請求 `401`（§4）」 |
| 待確認（屬安全政策，交 manager 判斷要不要問甲方） | — | 管理員用 PATCH 重設**自己**的密碼時，D-071 寫的是「該使用者所有 session 失效」，這樣呼叫者自己目前的 session 也會跟著失效（下一個請求就要重新登入）。建議照字面執行並寫進契約；前端只需要在 PATCH 自己的密碼成功後導向登入頁 |

實作提示（給下一張單的設計參考，非契約內容）：session 存在 Tomcat 記憶體裡，沒辦法依使用者列出所有 session。最簡單的做法是在 `app_user` 加一個 `session_epoch`（或 `password_changed_at`）欄位（V7，expand 型的 migration）。登入時把這個值存進 session；`SessionAuthenticationFilter.authenticate` 本來就會在每個請求重新載入使用者，這時順便比對，值不一致就清掉 session 並回 401。管理員重設密碼時把這個值 +1，而且要跟密碼更新放在同一個交易裡。`/auth/password` 不動這個值。
