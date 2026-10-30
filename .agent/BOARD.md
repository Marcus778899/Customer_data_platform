# 布告欄

<!-- 只記關鍵事項，上限 80 行；只有 manager 透過 scripts/board.py 修改 -->

## 目標與範圍

- 本輪：v0.2.0（單一租戶，D-051；拆版 D-055）：relay／迴圈改寫與壓測、使用者登入與角色、客戶資料庫＋搜尋＋客戶頁、外框＋受眾佔位、旅程列表（唯讀）、圖形軌跡（直線）
- 不做：多租戶與 RLS（D-051）、畫布／分支節點／旅程審核（v0.3.0，D-055、D-056）、資料來源串接（D-052、D-059）、受眾實作、真實通道、Redis、CDC

## 已定案決策（細節見 docs/decisions.md；要改先問甲方）

| 日期 | 編號 | 版本 | 決策 |
|---|---|---|---|
| 2026-10-17 | D-001 | v0.1.0 | 依甲方骨幹計劃書發想，團隊需審視其架構誤解 |
| 2026-10-17 | D-002 | v0.1.0 | 後端 Java Spring Boot、前端 React |
| 2026-10-17 | D-003 | v0.1.0 | 語言框架版本與版號由團隊決定 |
| 2026-10-17 | D-004 | v0.1.0 | 本輪範圍為 v0.1.0 |
| 2026-10-17 | D-005 | v0.1.0 | v0.1.0 含唯讀 Enrollment 軌跡查詢頁 |
| 2026-10-17 | D-006 | v0.1.0 | 背景任務佇列採 RabbitMQ（經 outbox、長等待不用 MQ） |
| 2026-10-17 | D-007 | v0.1.0 | Java 17，2027-06 前升級到 25 |
| 2026-10-17 | D-008 | v0.1.0 | spec 進 docs/spec/，LICENSE 保留所有權利 |
| 2026-10-17 | D-009 | v0.1.0 | 相依套件由團隊安排 |
| 2026-10-17 | D-010 | v0.1.0 | LICENSE 沿用 ../CDP/LICENSE，著作權人 Marcus Lin |
| 2026-10-17 | D-011 | v0.1.0 | Windows 開啟開發人員模式以建 symlink |
| 2026-10-17 | D-012 | v0.1.0 | .claude/agent-memory/ 加進 .gitignore |
| 2026-10-17 | D-013 | v0.1.0 | v0.1.0 API 與軌跡頁要認證（方式由 architect 提） |
| 2026-10-17 | D-014 | v0.1.0 | 軌跡頁個資要遮罩 |
| 2026-10-17 | D-015 | v0.1.0 | 查詢：客戶 ID／Enrollment ID＋狀態篩選 |
| 2026-10-17 | D-016 | v0.1.0 | 時間用瀏覽器本地時區，可看 UTC |
| 2026-10-17 | D-017 | v0.1.0 | 中性藍灰配色、中性書面語 |
| 2026-10-17 | D-018 | v0.1.0 | 旅程編輯畫布提前到 v0.2.0 |
| 2026-10-17 | D-019 | v0.1.0 | 事件攝取 API 用單一 Bearer 金鑰 |
| 2026-10-17 | D-020 | v0.1.0 | 客服帳號用 .env 一組共用帳密 |
| 2026-10-17 | D-021 | v0.1.0 | 遮罩採白名單 |
| 2026-10-17 | D-022 | v0.1.0 | 實作一次一到兩張，先 BE-01＋FE-01 |
| 2026-10-18 | D-023 | v0.1.0 | v0.1.0 不限請求大小，列為以後優化 |
| 2026-10-18 | D-024 | v0.1.0 | 事件派發重試 N 次後放棄並記錄 |
| 2026-10-18 | D-025 | v0.1.0 | FIX-02～04 都在 v0.1.0 做 |
| 2026-10-18 | D-026 | v0.1.0 | 多旅程部分失敗：成功的保留 |
| 2026-10-18 | D-027 | v0.1.0 | 重試設定採團隊建議值 |
| 2026-10-18 | D-028 | v0.1.0 | F-2、F-3 納入 v0.1.0；F-8 維持一筆列表 |
| 2026-10-18 | D-031 | v0.1.0 | v0.1.0 加 make demo-seed 示範資料（D-029、D-030 見 decisions.md） |
| 2026-10-18 | D-029 | v0.1.0 | make 只支援 Git Bash／Linux |
| 2026-10-18 | D-030 | v0.1.0 | 軌跡頁維持精確查詢，導覽列與瀏覽清單 v0.2.0 |
| 2026-10-18 | D-032 | v0.1.0 | v0.1.0 照現狀發版，UX 回饋進 v0.2.0 |
| 2026-10-18 | D-033 | v0.2.0 | v0.2.0 納入外框分頁、圖形軌跡、客戶搜尋、租戶登入 |
| 2026-10-18 | D-034 | v0.2.0 | 受眾分頁 v0.2.0 先佔位，v0.4.0 實作 |
| 2026-10-18 | D-035 | v0.2.0 | 發版後 board.py 快照到 tasks/job_history 再移除 done |
| 2026-10-18 | D-036 | v0.2.0 | 外部 review 修正（relay、執行緒、小交易）併入 v0.2.0（#72） |
| 2026-10-18 | D-037 | v0.2.0 | 不引入 CDC，維持輪詢 |
| 2026-10-18 | D-038 | v0.2.0 | v0.2.0 不設流量目標，先壓測 |
| 2026-10-19 | D-039 | v0.2.0 | 版本封存進 git，不再 ignore |
| 2026-10-19 | D-040 | v0.2.0 | 已發版派工單歸檔到 tasks/<版本>/，含版本代稱快照 |
| 2026-10-19 | D-051 | v0.2.0 | 代管、每家客戶一套單一租戶環境，主打資料整合 |
| 2026-10-19 | D-052 | v0.2.0 | 資料來源串接延後；整合以 PostgreSQL 解耦（含 ETL） |
| 2026-10-19 | D-053 | v0.2.0 | 委外客戶先當資料中樞，名單推給第三方平台發送 |
| 2026-10-19 | D-045 | v0.2.0 | 登入限速併入第一版 |
| 2026-10-19 | D-046 | v0.2.0 | 第二版驗收用縮減版場景一 |
| 2026-10-19 | D-047 | v0.2.0 | relay 新調校設定採團隊建議值 |
| 2026-10-19 | D-048 | v0.2.0 | 客戶搜尋完全比對，單筆直進客戶頁 |
| 2026-10-19 | D-049 | v0.2.0 | 產品暫名「客戶旅程平台」，導覽：旅程、客戶、受眾 |
| 2026-10-19 | D-050 | v0.2.0 | 旅程列表不顯示人數；迴圈不限圈數、畫布提醒 |
| 2026-10-19 | D-054 | v0.2.0 | 布告欄取消行數上限，決策依版本區分 |
| 2026-10-19 | D-055 | v0.2.0 | v0.2.0 拆版：畫布、分支、旅程審核移到 v0.3.0 |
| 2026-10-19 | D-056 | v0.2.0 | 檢視者／編輯者／管理員；旅程發佈須管理員審核（v0.3.0） |
| 2026-10-19 | D-057 | v0.2.0 | 首位管理員由團隊建立，之後走 API，第一版無使用者管理畫面 |
| 2026-10-19 | D-058 | v0.2.0 | 資料整合採收件區（landing），外部不直接寫核心表 |
| 2026-10-19 | D-059 | v0.2.0 | 收件區 v0.4.0、名單出口 v0.5.0、LINE 推送 v0.6.0 |
| 2026-10-19 | D-060 | v0.2.0 | 旅程審核：不可核准自己送的；每次發佈都審，停止不審留紀錄 |
| 2026-10-19 | D-061 | v0.2.0 | 管理金鑰建立旅程預設免審（留紀錄），各環境可用 .env 關閉 |
| 2026-10-19 | D-062 | v0.2.0 | v0.2.0 加唯讀旅程頁（標頭、版本切換、節點圖），併進 FE-03＋QA-05a |
| 2026-10-19 | D-063 | v0.2.0 | v0.2.0 從 schema 移除 tenant_id，廢除 CDP_TENANT_ID |
| 2026-10-19 | D-064 | v0.2.0 | CDP_TENANT_ID 暫留 .env，REFACTOR-01 合併後再刪 |
| 2026-10-19 | D-065 | v0.2.0 | 保留範本更新，補回編碼修正與決策版本欄 |
| 2026-10-21 | D-066 | v0.2.0 | 合併後本機清理可續做（chore merge-cleanup-resume），舊 worktree 資料夾一起清 |
| 2026-10-21 | D-067 | v0.2.0 | 流程檔案只由甲方經範本更新修改，作廢 merge-cleanup-resume |
| 2026-10-21 | D-068 | v0.2.0 | 前端第二批等範本修正（續做清理）後再開 |
| 2026-10-22 | D-069 | v0.2.0 | 前端瀏覽器實測移到階段收尾的 uat，qa 以自動測試為準 |
| 2026-10-23 | D-070 | v0.2.0 | React Flow 署名連結保留，不訂閱 |
| 2026-10-23 | D-071 | v0.2.0 | 管理員重設密碼強制登出該使用者所有 session，併進下一張後端單 |
| 2026-10-24 | D-072 | v0.2.0 | profile-store-api 作廢，用正確驗收範圍重開 |
| 2026-10-30 | D-073 | v0.2.0 | profile-search-api 先 sync 再追加 review 結果 |
| 2026-10-30 | D-074 | v0.2.0 | 依賴漏洞破例開單升版（deps-security-upgrade） |
| 2026-10-30 | D-075 | v0.2.0 | develop 只改文件的 push 跳過測試，資安掃描照跑 |

## 任務狀態

| 任務 | 負責 | 狀態 | 嘗試 | 驗收證據 |
|---|---|---|---|---|
| CHORE-06 | backend | done | 0 | PR #71 |
| DESIGN-03 | architect | done | 2 | PR #77 |
| UI-03 | designer | done | 1 | PR #76 |
| CHORE-07 | backend | done | 1 | PR #79 |
| FIX-09 | backend | done | 0 | PR #84 |
| DESIGN-04 | architect | done | 1 | PR #83 |
| CHORE-09 | backend | done | 0 | PR #86 |
| tenant-id-drop | backend | done | 2 | PR #92 |
| app-shell-login | frontend | done | 1 | PR #91 |
| scripts-fix-restore | backend | done | 0 | PR #90 |
| merge-cleanup-resume | backend | blocked | 0 | 作廢（D-067） |
| relay-loop-rewrite | backend | done | 0 | PR #99 |
| journey-customer-ui | frontend | done | 0 | PR #98 |
| compose-test-setup | architect | done | 0 | PR #97 |
| user-auth-api | backend | done | 0 | PR #104 |
| auth-session-design | architect | done | 0 | PR #107 |
| profile-store-api | backend | blocked | 0 | 作廢（D-072） |
| user-session-revoke | backend | review | 0 |  |
| profile-search-api | backend | qa | 0 |  |
| ci-speedup-design | architect | review | 0 |  |
| deps-security-upgrade | backend | in-progress | 0 |  |
| ci-docs-skip | architect | review | 0 |  |

## 卡住／待甲方決定

- 甲方要求（2026-10-24）：profile-search-api、user-session-revoke 合併後暫停，不開新單（例外：甲方 2026-10-31 要求 ci-speedup-design 調查 CI 加速）
- develop CI 的 Check 紅：LoginSessionAcceptanceTest.role_change_takes_effect_on_next_request 受測試執行順序影響（user-session-revoke 仲裁 TEST_WRONG，修正在該 PR 內），合併 user-session-revoke 後恢復
- CI 依賴漏洞掃描擋住所有 PR 合併：deps-security-upgrade 升版中（D-074），合併後 user-session-revoke、profile-search-api、ci-speedup-design（已核可）、ci-docs-skip（qa＋review 通過）再 sync
- deps-security-upgrade 補 LoginSessionAcceptanceTest 順序修正（解 CI 互卡）中；合併後 user-session-revoke、ci-speedup-design、ci-docs-skip、profile-search-api 依序 sync 合併（甲方 .wslconfig 已限記憶體，容器測試可平行）
- deps-security-upgrade：lock 後 worker 無須改程式但工具要求新 commit，待甲方決定 D-076

## 注意事項（踩過的坑）

- （無）
