# 派工單 DESIGN-01

- **類型**：docs
- **負責**：architect
- **目標**：出 v0.1.0 旅程引擎直線流程的設計、契約與骨架，並排出實作派工順序
- **背景**：範圍見 D-004、D-005、D-006；技術棧與分期見 docs/design/INIT-01.md（R1～R7 已核可）。
  docs/spec/ 有 3 處與 INIT-01 不一致（README 的 ../plan/ 斷鏈、next_wake_at 仍寫 db-scheduler、spec 05 管線入口未經 outbox→RabbitMQ），本單一併修訂。
  唯讀軌跡頁的畫面由 UI-01（designer）平行出；本單負責它要用的 API 契約。Docker 已啟動，可實測 Testcontainers 與 make infra-up（需調查 2）。
- **設計依據**：docs/design/INIT-01.md、docs/spec/、templates/design.md、templates/contract.md
- **相依**：無
- **負責檔案**：
  - `docs/design/DESIGN-01.md`
  - `docs/contracts/`
  - `docs/spec/`
  - `api/pom.xml`
  - `api/src/main/java/com/cdp/`（介面、型別、空實作骨架；不寫業務實作）
  - `api/src/main/resources/`
  - `api/src/test/resources/`
- **禁區**：scripts/、templates/、BOARD.md、README.md、docs/decisions.md、web/；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] 設計文件含元件與資料流、各實作單的負責檔案與驗收測試目錄、派工順序
  - [ ] 契約涵蓋事件攝取 API 與軌跡查詢 API（前端可依此 mock）
  - [ ] 骨架可編譯，ArchUnit 規則（節點不依賴 Repository、只有 relay 依賴 RabbitTemplate）已就位
  - [ ] docs/spec/ 的 3 處不一致已修正
  - [ ] 需調查、待決定以清單列出
- **驗證指令**：`make check`
- **回報檔**：`reports/DESIGN-01.json`

## 執行資訊（task.py 產生）

- Issue：#3
- 分支：`docs/design-01`（從 develop 開出）
- 工作目錄：`.worktrees\DESIGN-01`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-17

## 追加 1（2026-10-18，UI-01 的需調查，請在契約中一併回答）

UI-01 的設計稿與規格在分支 docs/ui-01（`git show docs/ui-01:docs/ui/enrollment-trace.md`；回報 reports/UI-01.json 列有所需欄位，來源 docs/spec/02 §2、§7、§8）。請在軌跡查詢 API 契約中回答：
1. 是否提供「依 profileId 列 Enrollment」（可選 status 篩選、分頁游標）；沒有的話列表頁無資料來源
2. 節點與旅程的人可讀名稱從哪來（旅程定義帶出？）
3. trace 與 deliveryHistory 的 nodeId 是否可對應
4. 可控時鐘快轉時，API 回傳的時間是模擬時間還是系統時間
5. decisionReason 是否可直接給客服看（語言、格式）
6. deliveryHistory 只留 50 筆（spec 02 §7），更舊紀錄是否提供查詢
錯誤回應需含 requestId，404 與 5xx 可分辨。對不上 UI-01 的地方列在設計文件，由 manager 轉 designer 修訂。

## 追加 2026-10-17

## 追加 2（2026-10-18）

- 追認負責檔案：`api/src/test/java/com/cdp/architecture/`（ArchUnit 規則，驗收條件要求、spec 04 §9 指定位置）。

## 追加 2026-10-17

## 追加（2026-10-18，同步 .gitignore）

- 本分支開在 CHORE-01 合併前，worktree 的 `.venv`、`node_modules` symlink 會被列為未追蹤，pr.py open 會拒絕。
- 追加負責檔案 `.gitignore`：內容改成與 develop 完全相同（`git show develop:.gitignore > .gitignore`），單獨一個 commit，確認 `git status --short` 為空。

## 追加 2026-10-17

## 追加 3（2026-10-18，甲方回覆 D-013～D-018）

- D-013 要認證：提出 v0.1.0 的認證方式（事件攝取 API 給外部系統、軌跡查詢 API 與頁面給客服），寫進契約（標頭、錯誤碼 401／403）；機密只在 .env.example 列變數名；需要甲方選的列為待決定。
- D-014 要遮罩：列出軌跡查詢回應中可能含個資的欄位（decision_reason、evaluated、trigger_snapshot、profile_snapshot…）與遮罩方式（後端遮罩，格式供 designer 呈現），寫進契約。
- D-018：畫布提前到 v0.2.0，更新 `docs/design/INIT-01.md` 的分期表（追加負責檔案）。
- 順修：契約 §4.1 `journey_name` 等可為 null 的欄位型別標成 nullable。
- 新的「UI-01 對不上的地方」（登入畫面、遮罩呈現）列在設計文件，manager 會轉 designer。
