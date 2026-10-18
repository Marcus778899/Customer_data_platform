# 布告欄

<!-- 只記關鍵事項，上限 80 行；只有 manager 透過 scripts/board.py 修改 -->

## 目標與範圍

- 本輪：v0.1.0：事件→Enrollment→等待→喚醒→Mock 發送（outbox→RabbitMQ）→結束，冪等、可控時鐘、節點軌跡；唯讀軌跡查詢頁（D-004、D-005）
- 不做：分支節點、多租戶 RLS、真實通道、受眾、Redis、旅程畫布（v0.2.0 以後）

## 已定案決策（細節見 docs/decisions.md；要改先問甲方）

| 日期 | 編號 | 決策 |
|---|---|---|
| 2026-10-17 | D-001 | 依甲方骨幹計劃書發想，團隊需審視其架構誤解 |
| 2026-10-17 | D-002 | 後端 Java Spring Boot、前端 React |
| 2026-10-17 | D-003 | 語言框架版本與版號由團隊決定 |
| 2026-10-17 | D-004 | 本輪範圍為 v0.1.0 |
| 2026-10-17 | D-005 | v0.1.0 含唯讀 Enrollment 軌跡查詢頁 |
| 2026-10-17 | D-006 | 背景任務佇列採 RabbitMQ（經 outbox、長等待不用 MQ） |
| 2026-10-17 | D-007 | Java 17，2027-06 前升級到 25 |
| 2026-10-17 | D-008 | spec 進 docs/spec/，LICENSE 保留所有權利 |
| 2026-10-17 | D-009 | 相依套件由團隊安排 |
| 2026-10-17 | D-010 | LICENSE 沿用 ../CDP/LICENSE，著作權人 Marcus Lin |
| 2026-10-17 | D-011 | Windows 開啟開發人員模式以建 symlink |
| 2026-10-17 | D-012 | .claude/agent-memory/ 加進 .gitignore |
| 2026-10-17 | D-013 | v0.1.0 API 與軌跡頁要認證（方式由 architect 提） |
| 2026-10-17 | D-014 | 軌跡頁個資要遮罩 |
| 2026-10-17 | D-015 | 查詢：客戶 ID／Enrollment ID＋狀態篩選 |
| 2026-10-17 | D-016 | 時間用瀏覽器本地時區，可看 UTC |
| 2026-10-17 | D-017 | 中性藍灰配色、中性書面語 |
| 2026-10-17 | D-018 | 旅程編輯畫布提前到 v0.2.0 |
| 2026-10-17 | D-019 | 事件攝取 API 用單一 Bearer 金鑰 |
| 2026-10-17 | D-020 | 客服帳號用 .env 一組共用帳密 |
| 2026-10-17 | D-021 | 遮罩採白名單 |
| 2026-10-17 | D-022 | 實作一次一到兩張，先 BE-01＋FE-01 |
| 2026-10-18 | D-023 | v0.1.0 不限請求大小，列為以後優化 |
| 2026-10-18 | D-024 | 事件派發重試 N 次後放棄並記錄 |
| 2026-10-18 | D-025 | FIX-02～04 都在 v0.1.0 做 |
| 2026-10-18 | D-026 | 多旅程部分失敗：成功的保留 |
| 2026-10-18 | D-027 | 重試設定採團隊建議值 |

## 任務狀態

| 任務 | 負責 | 狀態 | 嘗試 | 驗收證據 |
|---|---|---|---|---|
| INIT-01 | architect | done | 0 | PR #2 |
| DESIGN-01 | architect | done | 0 | PR #7 |
| UI-01 | designer | done | 1 | PR #8 |
| CHORE-01 | architect | done | 0 | PR #6 |
| BE-01 | backend | done | 0 | PR #13 |
| FE-01 | frontend | done | 0 | PR #11 |
| BE-04 | backend | done | 0 | PR #14 |
| FIX-01 | backend | done | 0 | PR #16 |
| BE-03 | backend | done | 1 | PR #20 |
| DESIGN-02 | architect | done | 0 | PR #19 |
| BE-02 | backend | done | 0 | PR #24 |
| INT-01 | frontend | done | 0 | PR #23；qa 端到端見 PR 留言 |
| FIX-02 | backend | done | 0 | PR #27 |
| CHORE-02 | architect | done | 0 | PR #28 |
| FIX-04 | backend | qa | 1 |  |

## 卡住／待甲方決定

- 後續修正單 FIX-02／03／04 見 DESIGN-01「後續實作項」（D-025）
- 後續：BE-03 review 六項（exists 查詢、q 欄位路徑、UNION ALL、readOnly 交易、masked_paths 跳脫、定義缺失記錄）
- 後續：BE-02 review（移除 missingQueuesFatal、relay 退避與 mandatory、DB 重排退避等）併入 FIX-03
- 後續：INT-01 review（403 文案補 UI 規格、403 單元測試、ErrorBox 註解）
- 後續：FIX-02 review（hikari data-source-properties、advice 規則用 meta-annotation、javadoc、406/404 單元測試；v0.2.0 filter 例外格式）
- 後續：FIX-03 合併後 architect 出 V4 收緊 event CHECK 為雙向（CHORE-02 review）

## 注意事項（踩過的坑）

- （無）
