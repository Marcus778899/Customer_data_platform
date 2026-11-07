# UI：旅程建立與審核（journey-builder-ui，v0.3.0）
- 設計稿：`docs/ui/journey-builder/index.html`（30 個畫面狀態，含 375／800px iframe 預覽；共用元件樣式 `base.css` 複製自 `journey-platform`，擴充在 `builder.css`、`round2.css`（第 2 輪覆寫），token 用 `web/src/styles/tokens.css`，無新 token）
- 延伸 `journey-platform.md`（UI-03）：登入、客戶搜尋、列表基底等未改動畫面仍以它為準；本稿取代它「畫布編輯、審核、圖形軌跡（分支）、旅程頁」的畫法。設計系統見 `design-system/customer-journey-platform/MASTER.md`（已補「v0.3.0 擴充元件」）
- 契約：`journeys.md` §3（草稿、送審、審核、停止）、§4（節點與條件）、§5（`node-stats`）、journey-engine §4.1（`profile_name`、`profile_identifier`）；`auth.md` §2。決策：D-050、D-056、D-060、D-061、D-079、D-080、D-082
## 畫面
| 畫面（設計稿 id） | 重點 |
|---|---|
| 畫布編輯（`ed-overview`、`ed-message`、`ed-condition`、`ed-panels`、`ed-settings`） | 滿版畫布（圖約 80% 整張進一屏，節點內文字放大補償）；頁首兩列格線（第一列麵包屑＋版本流程「草稿→審核中→上線」，第二列標題＋按鈕，停用原因在按鈕下方，標題位置固定）；節點工具列（訊息、等待、等事件、條件、A/B 分流、合流、達標、退出、結束；≤640px 收成「＋ 新增節點」）；狀態列放迴圈提醒（不限圈數，D-050，附「定位」）與儲存狀態；縮放與小地圖；選取節點開右側面板；訊息節點的管道與範本是手動輸入代碼（契約無清單 API）；條件每個一行句子、白話欄位名，原始代碼收進進階資訊；「旅程設定」放重新進入、達標、隨時退出 |
| 驗證與衝突（`ed-validate`、`ed-banners`） | 錯誤節點紅框＋「有錯誤」，面板每項「定位節點」；`node_id` 為 null 改「前往旅程設定」；`field`／規則號收進「進階資訊」；提示條：DRAFT_CONFLICT、DRAFT_IN_REVIEW、JOURNEY_ABORTED、儲存失敗、VALIDATION_FAILED |
| 載入、空、錯誤（`ed-loading`、`ed-empty`、`ed-errors`） | 骨架；新草稿只有開始的引導；5xx 重試、草稿 404、檢視者進編輯網址 403 |
| 審核（`rv-matrix`、`rv-bars`、`rv-dialogs`、`rv-review`、`rv-queue`、`rv-history`、`rv-history-stopped`） | 角色×狀態矩陣；各角色按鈕列；送審／核准／退回（原因必填）／撤回／停止（原因必填＋輸入旅程名稱確認）／核准失敗對話框；管理員審核頁顯示「跟前一版比」新增／修改標記；列表「待審核」篩選；紀錄時間軸（含管理金鑰建立、停止併成一列） |
| 節點人數與軌跡（`st-view`、`st-states`、`tr-branch`） | 旅程頁節點卡下方「進入次數／目前在此」，選取節點看明細；載入骨架、失敗（圖照常）、尚無客戶；軌跡：走過粗藍線、沒走虛線、迴圈點狀紫線「再等一輪」、「第 N 次經過」 |
| UX 小修（`ux-*`） | 見下表 |
## 審核可見性（完整矩陣見 `rv-matrix`）
停用鈕一律保留並附原因（`aria-disabled`＋`aria-describedby`，仍可聚焦）。檢視者：只能看。編輯者：編輯、送審、捨棄草稿、停止；送審中只能撤回自己送的。管理員：另可核准、退回、撤回任何人送的；自己送的核准停用並說明（D-060）。每次發佈都審；停止不審但寫入紀錄；`DRAFT` 旅程停用「捨棄草稿」「停止」。管理金鑰建立只出現在紀錄（D-061）。
## UX issue 對照
| issue | 規格 |
|---|---|
| #144 客戶頁 1280px | 旅程表改三欄（旅程＋目前位置／狀態／時間）、`table-layout: fixed`；左欄 300px；`td` 首欄不斷字；1100px 以下單欄 |
| #146 登入後首頁 | 登入後與 `/` 進「旅程」列表，側欄「旅程」亮起；舊軌跡查詢頁網址導向「客戶」搜尋；不需新畫面 |
| #178 h1 焦點 | `[tabindex="-1"]:focus` 不畫外框；互動元件 `:focus-visible` 才有環（`ux-focus`） |
| #180／#187 客戶標示 | 全站同一規則：姓名 → 第一個識別碼（後端已遮罩）→「未具名客戶」；列表客戶欄兩行（標示＋小字識別資訊）；ID 不出現（`ux-list`、`ux-customer*`） |
| #188 搜尋按鈕 | 輸入框與按鈕同一排、緊鄰、同高 48px；說明文字刪「（D-048）」（`ux-search`） |
| #179／#147／#148 文案 | 節點名全站共用白話對照；等待摘要用本地時間；刪除 D-編號；空白搜尋不顯示「查無」（`ux-copy`） |
## 狀態與無障礙
載入＝骨架（`role="status"`）；錯誤＝`role="alert"`＋請求編號＋重試；權限不足＝停用鈕附原因或整頁 403。對話框 `role="dialog" aria-modal`，焦點進入第一欄、Esc 取消、回到觸發鈕。圖：節點是 `<button>`，選取 `aria-pressed`；邊與裝飾 `aria-hidden`；每張圖附「文字版節點清單」。狀態一律文字＋顏色＋圖示；對比 >= 4.5:1；reduced-motion 關閉轉場。
窄螢幕：≤1100px 面板排到圖下方；≤640px 工具列與按鈕堆疊、表格／矩陣轉卡片、互動元件 >= 44px；畫布以左右捲動檢視，編輯建議桌面，審核與停止在窄螢幕可完成。
## 自行決定
| 項目 | 決定 |
|---|---|
| 節點稱呼 | 畫面用「退出」（避免與連線「出口」混淆）、「達標」、「等事件」；節點卡不顯示型別名 |
| 畫布排版 | 由左到右，沿用 architect 的 dagre 方向；縮放約 80% 讓整張圖進一屏 |
| 屬性面板 | 右側抽屜蓋在畫布上，不縮小畫布；窄螢幕排到下方 |
| 送審說明與審核 | 送審說明、核准備註選填（200 字）；核准頁顯示「跟前一版比」（前端比對草稿與最新版，不需新 API） |
| 停止確認 | 要輸入旅程名稱才能按（不可恢復） |
| 衝突處理 | 載入最新草稿＋「複製我的修改」，不自動合併 |
| 人數文案 | 依契約 §5：「人數」＝`entered`（同一客戶繞迴圈只算 1）、「進入次數」＝`visits`；卡片只放人數與目前，進入次數與各出口人次在面板 |
| 時間格式 | 全站一種：今天／昨天 HH:mm → 同年 MM-DD HH:mm → 跨年 YYYY-MM-DD HH:mm |
| 提示條配色 | 用 token 的 warning／info／danger 前景色淡化當底（不用檸檬黃、天空藍）；停止對話框用 danger |
| favicon（#150） | `web/public/favicon.svg`：側欄深藍灰底、淺藍起點分成兩條路，不含文字（產品名未定）；`web/index.html` 的 `<link rel="icon">` 由 frontend（ux-polish-web）加 |
## 待決定與需調查
- 待決定：無。契約缺口已由 architect 回應（PR #194）並反映在設計稿：管道／範本改手動輸入；#180 用 `profile_name`／`profile_identifier`；待審核列表用 `latest_review`；人數用 `entered`／`visits`／`out_ports`。尚未畫：`EventSourceNode.event_filter`（DESIGN-05 待決定 3，決定支援後補進入條件面板）
- 需調查：無（沿用 `@xyflow/react`＋dagre；設計稿的畫布為手刻示意）
