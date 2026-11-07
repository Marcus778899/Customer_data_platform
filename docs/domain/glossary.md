# 術語

<!-- 中英對照與程式命名；畫面用語以「畫面」欄為準（D-079、D-080）。規則細節見 rules.md -->

| 術語 | 程式命名 | 畫面 | 定義 | 來源 |
|---|---|---|---|---|
| 旅程 | `journey` | 旅程 | 一組自動化流程；有生命週期狀態（`DRAFT`／`ACTIVE`／`ABORTED`） | spec 03、`journeys.md` §1 |
| 版本 | `journey_definition`、`version` | 版本 | 核准後建立、不可變的旅程內容；新進入的客戶綁定當下最新版本 | spec 03 §1、D-056 |
| 草稿 | `draft`、`revision` | 草稿 | 尚未發佈、可編輯的內容；每存一次 `revision` +1；一個旅程最多一份 | `journeys.md` §3 |
| 送審／核准／退回／撤回 | `submit`／`approve`／`reject`／`withdraw` | 送審／核准／退回／撤回 | 草稿發佈的審核動作，見 rules.md「審核」 | D-056、D-060 |
| 審核紀錄 | `Review`（`journey_review`） | 審核紀錄 | 每次送審一筆，終態後不可改、不可刪 | `journeys.md` §3.2 |
| 停止 | `stop`、`ABORTED` | 停止 | 旅程終止，不可恢復；不經審核 | D-060、rules.md「停止」 |
| 旅程進度 | `Enrollment` | 旅程進度 | 一位客戶在一個旅程版本中的一次參與 | D-079 |
| 軌跡 | `trace`（`node_trace`） | 經過的步驟 | 旅程進度依序經過的節點與原因 | journey-engine §4 |
| 節點 | `node` | 步驟 | 旅程圖上的一格；型別見 rules.md「節點」 | spec 03 §4、spec 04 |
| 出口 | `outputs`、`out_port` | （依節點：是／否、發生／逾時、組別） | 節點往下一步的連線名稱 | spec 03 §4 |
| 分支節點 | `ConditionNode`、`SplitTestNode`、`WaitForEventNode` | 條件判斷、分流、等待事件 | 有兩個以上出口的節點 | `journeys.md` §4 |
| 匯流 | `MergeNode` | 匯流 | 把多條分支接回同一條 | `journeys.md` §4 |
| 目標 | `goal`、`GoalNode`、`GOAL_MET` | 達成目標 | 客戶完成指定事件即結束並記為達成 | spec 03 §3 |
| 全域退出 | `global_exit`、`EXITED` | 提前離開 | 任何時候符合條件即離開旅程 | spec 03 §3 |
| 重入 | `reentry`、`dedup_key` | 重複進入 | 同一客戶能否再次進入同一旅程 | spec 03 §3、spec 02 §6 |
| 節點統計 | `node-stats` | 各步驟人數 | 每個節點的進入人數、停留中人數、各出口人數 | `journeys.md` §5 |
| 明文清單 | `plain keys` | — | 可明文顯示的屬性鍵；其餘遮罩 | D-021、rules.md「遮罩」 |
| 客戶標示 | `profile_name` | 客戶姓名／未具名客戶 | 畫面上辨識客戶的文字；規則由 `docs/ui/` 定 | D-080、D-082、#180、#187 |
