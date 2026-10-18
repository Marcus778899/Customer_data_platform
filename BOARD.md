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
| BE-03 | backend | in-progress | 0 |  |

## 卡住／待甲方決定

- 後續修正：CDP_TRACE_PLAIN_KEYS 空值＝全遮，但 check_env 把空值當未填；暫填不存在的鍵（例 none），之後由 architect 調整
- 後續修正：契約 §5 未定義 405 的 code，交 architect 補
- 後續修正：ApiExceptionHandler 泛用處理會把 controller 內的 AccessDeniedException 轉 500，加 method security 前要處理
- 後續修正（BE-04 review）：GET 預設拒絕白名單、record toString 遮蔽機密、補閒置逾時測試、cookie 設定單一來源、啟動時驗證金鑰

## 注意事項（踩過的坑）

- （無）
