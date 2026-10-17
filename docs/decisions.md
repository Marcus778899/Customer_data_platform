# 決策紀錄

需要甲方決定的事都在這裡，由 manager 維護。流程：agent 提問 → manager 寫進「待決定」→ 甲方回覆 → manager 移到「已決定」。
其他文件一律用編號引用（例：見 D-003），不重寫內容。

## 待決定

<!-- 範本：
### D-001 一句話問題
- 背景：為什麼要決定（1–2 行）
- 選項：A. … ／ B. …
- 建議：A，理由一行
- 提出：architect（DESIGN-01）
-->

### D-004 本輪範圍是否為 v0.1.0
- 背景：v0.1.0＝計劃書 Phase 1a 縮減版，可獨立驗收（見 docs/design/INIT-01.md「分期與版號」）
- 選項：A. 照設計的 v0.1.0 ／ B. 再縮（去掉軌跡頁或 outbox）
- 建議：A，冪等延後會改交易形狀（R4）
- 提出：architect（INIT-01）

### D-005 v0.1.0 是否含唯讀 Enrollment 軌跡查詢頁
- 背景：計劃書的畫布到 Phase 4 才有，前三期沒有任何 UI（R7）
- 選項：A. 含唯讀軌跡頁 ／ B. 只做 API
- 建議：A，即「為什麼這客戶沒收到」的客服畫面，前後端可平行
- 提出：architect（INIT-01）

### D-006 背景任務（C 類）用什麼佇列
- 背景：計劃書引入 RabbitMQ，但仍需先寫 outbox（R3）
- 選項：A. v0.1–v0.2 只用 Postgres outbox 當佇列，v0.3.0 重評 ／ B. v0.3.0 照計劃書上 RabbitMQ ／ C. JobRunr
- 建議：A，少一套基礎設施，outbox 本來就要做
- 提出：architect（INIT-01）

### D-007 Java 版本與升級時程
- 背景：本機只有 Temurin 17（支援至 2027-10-31）；Temurin 25 LTS 至 2031-09-30；Boot 4.1 OSS 支援至 2027-07-31
- 選項：A. 維持 17，2027-06 前與 Boot 升級同一張單升到 25 ／ B. 現在就裝 Temurin 25 ／ C. 維持 17 不定時程
- 建議：A，v0.1.0 用不到 25 的功能
- 提出：architect（INIT-01）

### D-008 計劃書與 spec 是否複製進本 repo
- 背景：CI、worktree、reviewer 看不到 `../CDP`；`../CDP` README 註明規格不進版控，而本 repo 是 public GitHub repo
- 選項：A. spec 複製到 `docs/spec/` 由本 repo 維護、計劃書只引用 ／ B. 兩者都複製成唯讀參考 ／ C. 不複製
- 建議：A，設計單要能引用 spec 章節
- 提出：architect（INIT-01）

### D-009 前後端相依套件核可
- 背景：清單與用途見 docs/design/INIT-01.md 與 reports/INIT-01.json（Spring Boot starters、JDBC、PostgreSQL、Flyway、Testcontainers、ArchUnit、JSON Schema 驗證；React、Vite、TypeScript、Vitest、ESLint 等）
- 選項：A. 全部核可 ／ B. 逐項核可
- 建議：A
- 提出：architect（INIT-01）

## 已決定

### D-001 產品方向
- 決定：依甲方的骨幹計劃書 `../CDP/docs/plan/backbone-plan.md`（規格在 `../CDP/docs/spec/`）發想；甲方表示裡面部分架構可能有誤解，團隊要審視並提出修正
- 理由：甲方提供
- 影響：INIT-01 及之後所有設計單
- 日期：2026-10-18

### D-002 後端用 Java Spring Boot、前端用 React
- 決定：後端 Java Spring Boot；前端 React
- 理由：甲方指定
- 影響：INIT-01
- 日期：2026-10-18

### D-003 版本由團隊定調
- 決定：語言／框架版本與分期對應的版號，甲方授權團隊討論決定，結果寫在設計文件裡回報甲方
- 理由：甲方授權
- 影響：INIT-01
- 日期：2026-10-18

<!-- 範本：
### D-001 一句話問題
- 決定：A. …
- 理由：一行
- 影響：docs/contracts/todos.md §2、BE-01
- 日期：YYYY-MM-DD
-->
