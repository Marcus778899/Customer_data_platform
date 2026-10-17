# UI：Enrollment 軌跡查詢（UI-01）

- 設計稿：`docs/ui/enrollment-trace/index.html`（瀏覽器直接打開）
- 設計 token：`web/src/styles/tokens.css`（設計稿相對路徑引用同一份）
- 契約：`docs/contracts/journey-engine.md` §1.2、§4、§5（分支 docs/design-01；欄位以此為準，下文 snake_case 即契約欄位）
- 參考來源：無（D-005：唯讀，不能操作旅程）

## 畫面

| 畫面 | 用途 | 進入方式 |
|---|---|---|
| 登入 | 帳號＋密碼（D-013）；頁首顯示帳號與「登出」 | 未登入或任何 API 回 401 時導向 `/login`，成功後回原頁 |
| 查詢＋列表 | 輸入 `q`（客戶 ID 或 Enrollment ID，精確比對），可依 `status` 篩選 | 首頁 `/` |
| 詳情 | 一筆 Enrollment 的現況、節點軌跡、發送歷史、觸發事件 | 列表點擊；或直接貼 Enrollment ID 查到唯一一筆 |

詳情由上而下：標題與狀態 → 「目前狀況」一句話摘要 → 節點軌跡（舊到新）→ 發送歷史 → 觸發事件快照（預設收合）。
摘要由 UI 以 `status`／`wake_condition`／`delivery_history` 組出，回答「走到哪、為什麼沒收到」；Enrollment 已 COMPLETED 但有 `PENDING` 發送時，固定句型「目前狀況：旅程已走完，但有 N 則訊息仍待送，尚未送出。」（設計稿同）。
軌跡第一列是 EventSourceNode；`node_type` 用完整名（WaitDelayNode…）；出口 `next`／`sent`，仍在節點或終點顯示「—」。
`journey_name` 必有值；`node_name` 為 null 時顯示 `node_type` 並標「未命名」。
遮罩（D-014）：只有 `masked_paths` 列出的值才標「已遮罩」徽章（文字，不比對 `***`）；詳情上方一句說明「無法解除」，沒有解除入口，也不提供複製遮罩值的功能（畫面上從不出現原文）。
發送歷史顯示 `template_id`、`channel_id`（`channel_type` 一律 MOCK）；訊息類型為 null 顯示「—」。結果：待送、已送出、發送失敗、已取消（`SKIPPED` 為已略過＋`skip_reason`，v0.1.0 不會出現）。

## 狀態

| 畫面 | 空 | 載入中 | 錯誤 | 成功 |
|---|---|---|---|---|
| 登入 | 初始：空白表單 | 按鈕「登入中…」、欄位唯讀 | 401：「帳號或密碼不正確」（唯一一種）；400：「請輸入帳號與密碼」；5xx：附 `request_id` | 204 後回原頁；session 逾時顯示「登入已逾時，請重新登入」 |
| 查詢＋列表 | 初始：提示輸入；查無（200 `items: []`）：說明可能原因 | 骨架列，按鈕顯示「查詢中…」 | 400／5xx：紅色提示＋`request_id`＋「重試」 | 表格，筆數＋「載入更多」（`next_cursor`） |
| 詳情 | 發送歷史無資料：「尚無發送紀錄」；`profile_snapshot` 為 `{}`：「此版本未記錄客戶屬性快照」 | 骨架區塊 | 404 `ENROLLMENT_NOT_FOUND`（只在詳情）與 5xx 分開寫，皆附 `request_id` | 見上，終態另顯示 `terminated_at` 與 `termination_reason`；發送歷史有「載入更早紀錄」（`delivery_history_next_cursor`） |

## 互動

| 操作 | 結果 | 回饋（文案、焦點移動） |
|---|---|---|
| 送出查詢（Enter／按鈕） | 帶參數查列表，網址同步 `?q=` 方便分享 | 載入中朗讀「載入中」；完成後焦點移到結果標題 |
| 點列表一列的連結 | 進詳情，網址 `/enrollments/:id` | 焦點移到詳情 h1 |
| 回到列表 | 回上一頁並保留查詢字串與捲動 | 焦點回到剛點的那一列 |
| 登出 | `POST /auth/logout` 後回登入頁 | 焦點到帳號欄 |
| 重試／重新整理 | 重打同一請求 | 顯示「資料更新於」`as_of`（後端時間） |
| 展開「判斷時取得的值」「觸發事件」 | 顯示 `evaluated`、`trigger_snapshot` | 原生 details，鍵盤 Enter／Space |
沒有任何寫入操作：不放暫停、重送、編輯按鈕（D-005）。

## 窄螢幕（< 640px）
- 列表表格轉卡片（每列一張，欄名在值前）；查詢欄位與按鈕垂直堆疊，觸控目標高 >= 44px
- 詳情鍵值對單欄；發送歷史同列表轉卡片；軌跡時間軸不變（設計稿末節有 375px 預覽）

## 無障礙

- 對比：文字與狀態色（fg/bg）皆 >= 4.5:1（實測 6.0–14.8）；輸入框邊框 >= 3:1；焦點環 3px 藍色
- 鍵盤：全部可用 Tab／Enter 操作，無 hover 才出現的內容；日期完整值放可見文字，不只放 title
- 讀屏標籤：狀態一律文字（不只靠顏色）；載入 `role="status"` ＋ `aria-busy`；錯誤 `role="alert"`；軌跡用 `<ol>`，目前節點 `aria-current="step"`；表格有 `caption` 與 `scope`；登入錯誤 `role="alert"` 並以 `aria-describedby` 關聯欄位，密碼欄 `autocomplete`；尊重 `prefers-reduced-motion`

## 待甲方決定／需要調查

- 待決定（編號由 manager 指派；認證 D-013、遮罩 D-014 已定案）：
  1. 時間顯示時區（預設：瀏覽器本地時區，並可看到 UTC 原值）
  2. 文案語氣與品牌色（目前中性配色、中性書面語）
  3. 查詢是否還要依旅程、時間範圍篩選（契約目前只有 `q`＋`status`）
- 需調查：無（上一輪 5 項已由契約回答）。已知落差：契約 `status` 可多選，設計稿用單選下拉，是否多選待甲方確認。帳號來源 D-020、遮罩名單 D-021 由 architect 提案，不影響畫面。
