# v0.2.0 UX 參考：旅程平台做法
查詢日 2026-10-19；只整理做法與取捨，不做決策。標「建議」者為研究者建議。

**結論**
- 常見骨架：左側分區導覽 → 旅程列表（狀態篩選）→ 畫布（分支＝split／true-false，等待＝wait／delay）→ 客戶 profile 內看個人歷程。
- 單人路徑：Customer.io、MoEngage 有官方可證實的個人旅程紀錄／路徑視覺化；Braze 統計為步驟層級，個人層級 Canvas 歷程頁查不到。
- 台日 LINE 工具（MAAC、Super8、Omnichat）官方功能頁取不到，畫布與客戶搜尋細節查不到。

| 項目 | 做法與例子（來源編號見文末） | 適用性 |
|---|---|---|
| 外框／導覽 | Braze：workspace 切換，依品牌、地區、產品線或團隊組織內容［1］，位置文件說法不一（側欄 vs 頂部）［1][2］；Iterable：Messaging > Journeys［7］；Klaviyo：Flows 內有 Analytics 分頁［9］ | 多品牌租戶可借鏡 Braze（建議） |
| 旅程列表 | Braze：All Statuses 篩選，狀態含 Draft、Active、Stopped、Archived、Idle［4］；Salesforce：可依名稱、活動類型、狀態、日期篩選（Trailhead）［16］；Klaviyo 可自訂欄位：Revenue、Conversions、Conversion rate、Deliveries、Unique recipients［9］ | 狀態＋可選欄位 |
| 畫布節點 | Braze：Decision Split、Action／Audience／Experiment Paths、Delay，最多 200 steps［3］；Iterable：Start、Message、Delay、Filter、User Update 等 tile［8］；Customer.io：True/False、Multi-split、Random cohort、Exit block［12］ | 卡片節點可參考分類 |
| 分支語意 | Customer.io：Multi-split 依序取第一個符合路徑，其餘走 fallback；True/False 不支援 OR［12］；Salesforce：由上而下，第一符合者勝，其餘走 Remainder［16］ | 「第一符合＋fallback」為常見語意 |
| 等待 | Insider：Wait for Event 可設 timeout，超時可前進或移出［15］；Salesforce：等待期間評估是否達成目標或分流條件［16］ | 等待節點附超時出口 |
| 循環／重入 | Braze：entry controls 決定能否重入，重入可能收到多次訊息，同一節點去重［3］；HubSpot：預設只入一次，需開 Re-enroll［14］；畫布上循環的圖示：查不到 | 重入規則宜明確設定 |
| 單人路徑 | Customer.io：Campaign 的 Journeys 分頁與個人 profile 皆可看，每筆含進入／離開時間、收到的訊息、轉換［11］；MoEngage：Flows Visualization 可選客戶檢視其路徑（2026-07 部落格）［17］ | 最貼近甲方需求 |
| 節點人數 | Braze：Canvas 各 component 顯示進入、進下一步、離開人數［5］（僅搜尋摘要，未全文驗證）；Insider：Wait 節點顯示等待中與超時人數［15］；Klaviyo：僅流程層級 unique recipients，逐步人數查不到［9］ | 每節點人數為常見做法 |
| 客戶搜尋 | Braze：external user ID、Braze ID、電話（轉 E.164）、push token、user alias［6］；Customer.io：email 前綴或 @網域［13］；Klaviyo：姓名、email、完整電話［10］；Iterable：email 或 user ID［7］ | 單一搜尋框支援多欄位（建議） |

**對 v0.2.0 可借鏡（建議）**
1. 搜尋：單一搜尋框同時接受 email、手機、外部 ID、ULID（參考 Braze、Klaviyo、Customer.io）。
2. 客戶 profile 頁內嵌「旅程紀錄」列表，點進去才是軌跡圖（Customer.io 模式）。
3. 旅程列表：Draft／Active／Stopped／Archived 狀態篩選（Braze 模式）。
4. 畫布：分支採 multi-split（第一符合＋fallback）與 true/false；等待節點附超時出口。
5. 軌跡圖：已走過節點標示，每節點顯示進入／離開人數（先確認個人層級是否可行）。
6. 側欄分區（如受眾、旅程、訊息範本、報表、設定）為建議，來源未給固定清單；工作區切換參考 Braze［1］。

**需注意的坑**
1. 多數官方 URL 會改版或 404（Braze 有 /docs/ 與 /de/ 等多版本；Customer.io 路徑已移動；MAAC 下載頁 404［20］），定稿前需再核對。
2. 台日工具：L Step 有分支設定說明［18］但無人數與畫布資訊；Omnichat 僅中文部落格，多帳號說法來源不明［19］；MAAC、Super8 官方功能頁查不到。
3. 查不到：循環畫法、Iterable 狀態名稱與列表篩選、Salesforce 列表 Stopped 定義；Braze 個人路徑重建在社群建議用 Currents（非官方）。不可推測。
4. Braze「Search Users」與 User Lookup 規則不同（後者只接受 external_id／braze_id）［6］，搜尋規則勿混用。

**來源**
[1] https://braze.com/docs/user_guide/administrative/access_braze/braze_dashboard ・ [2] https://www.braze.com/docs/user_guide/administer/global/create_and_manage_workspaces ・ [3] https://www.braze.com/docs/user_guide/engagement_tools/canvas/create_a_canvas/create_a_canvas ・ [4] https://www.braze.com/docs/user_guide/engagement_tools/canvas/get_started/canvas_status
[5] https://braze.com/docs/user_guide/analytics/reporting/canvas_analytics ・ [6] https://braze.com/docs/user_guide/engagement_tools/segments/using_user_search ・ [7] https://support.iterable.com/hc/en-us/articles/10040142041876-Managing-Journeys ・ [8] https://support.iterable.com/hc/en-us/articles/34371877778964-Adding-Journey-Tiles
[9] https://help.klaviyo.com/hc/en-us/articles/52037859248283-how-to-use-the-flow-analytics-dashboard ・ [10] https://help.klaviyo.com/hc/en-us/articles/115005247088-Profiles-in-Klaviyo ・ [11] https://docs.customer.io/journeys/campaign-journeys/ ・ [12] https://docs.customer.io/journeys/branches/
[13] https://docs.customer.io/messaging/profiles/filter-search.md ・ [14] https://knowledge.hubspot.com/workflows/create-workflows ・ [15] https://academy.insiderone.com/docs/architect-journey-use-wait-for-event.md ・ [16] https://www.salesforcetutorial.com/decision-split/ ・ https://trailhead.salesforce.com/content/learn/modules/journey-campaign-analytics-and-optimization/measure-journey-campaign-performance
[17] https://www.moengage.com/blog/flows-visualization-and-versioning ・ [18] https://linestep.jp/2023/07/02/lstep-scenario-branch/ ・ [19] https://blog.omnichat.ai/tw/omnichat-salesforce-integration/ ・ [20] https://www.cresclab.com/en/download/maac
