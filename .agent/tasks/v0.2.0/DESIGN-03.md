# 派工單 DESIGN-03

- **類型**：docs
- **負責**：architect
- **目標**：出 v0.2.0 的設計：範圍拆版提案、架構、API 契約、資料模型與 migration 規劃、實作派工順序
- **背景**：v0.2.0 範圍見 BOARD.md「目標與範圍」。來源：docs/design/INIT-01.md 的 v0.2.0 列（分支節點、G1、G3、版本綁定、RLS、最小 ProfileStore、旅程畫布 D-018），加上甲方驗收回饋 D-033（產品外框＋分頁、圖形軌跡、客戶搜尋、租戶使用者登入取代 D-020）、D-034（受眾分頁佔位）、D-036～D-038（外部 review 修正與壓測，分析見 GitHub #72）、D-037（不引入 CDC）。
  範圍很大：請先估工並提出拆成 v0.2.0／v0.3.0（或更多）的方案，列為待決定交甲方。UI 由 designer 在 UI-03 平行出；畫面需要的資料由本單的契約提供，欄位對不上時列「需調查」由 manager 轉達。
- **設計依據**：docs/design/INIT-01.md；docs/design/DESIGN-01.md；docs/contracts/journey-engine.md；docs/spec/；docs/research/ux-journey-platforms.md；GitHub #72、#44 及 backlog 標籤中屬於 v0.2.0 的項目；docs/decisions.md
- **相依**：無
- **負責檔案**：
  - `docs/design/DESIGN-03.md`（新增）
  - `docs/contracts/`
  - `docs/spec/`
  - `docs/design/DESIGN-01.md`（只改被 v0.2.0 取代的段落，例：#12 交易邊界、relay 流程）
  - `.env.example`（只新增 v0.2.0 需要的變數名稱與說明，不填真實值）
- **禁區**：程式碼（`api/`、`web/`）、`scripts/`、`templates/`、`docs/ui/`、`docs/decisions.md`、README.md、BOARD.md；不可讀 .env
- **驗收測試**：無
- **驗收條件**：
  - [ ] 拆版提案：每版的範圍、驗收方式、粗估工作量（以派工單數計），列為待決定並給建議
  - [ ] 第一版的架構：多租戶（RLS、租戶使用者登入與角色、工作區切換）、分支節點與版本綁定、最小 ProfileStore 與客戶搜尋（email／手機／外部 ID／ULID）、圖形軌跡所需資料（每節點進入／離開、客戶走過的路徑與目前位置）
  - [ ] relay 與輪詢迴圈改寫依 #72 的建議（available_at lease、交易外整批發送、條件式回寫、每迴圈獨立執行緒、小交易、滿批續做），維持「中斷重啟恰好一則」並列出新增的驗收情境與壓測項目（D-038）
  - [ ] API 契約（含錯誤碼、權限與租戶隔離）足以讓前後端與 qa 照做；migration 規劃
  - [ ] 實作派工順序（每張的負責角色、相依、驗收測試目錄），一次一到兩張（D-022）
  - [ ] 需調查、待決定以清單列出
- **驗證指令**：`make check`
- **回報檔**：`reports/DESIGN-03.json`

## 執行資訊（task.py 產生）

- Issue：#74
- 分支：`docs/design-03`（從 develop 開出）
- 工作目錄：`.worktrees\DESIGN-03`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `reports/`

## 追加 2026-10-18

- 追加 1（UI-03 需調查，designer 轉來；設計稿 `.worktrees/UI-03/docs/ui/journey-platform/index.html`、規格 `docs/ui/journey-platform.md`，分支 docs/ui-03）：契約須回答或列為待決定：
  1. spec 03 §4 節點結構沒有座標：畫布存節點位置，或一律自動排版
  2. 旅程列表、定義、版本 API 欄位（狀態、updated_by、進行中人數）
  3. 軌跡每步的 visit 序號、出口、decision_reason、evaluated；依 Enrollment 綁定的版本取當時的旅程定義
  4. 搜尋 email／手機：雜湊索引完全比對或支援前綴、手機是否正規化為 E.164、回傳 match_field
  5. 客戶頁屬性白名單包含哪些欄位、姓名欄位是否存在
  6. 使用者、工作區成員、角色、工作區切換 API 與 session 逾時
  7. 草稿儲存衝突偵測；驗證結果格式（spec 03 §6 規則編號、節點 id）
- designer 的待決定中與架構相關者，請在設計提出建議：角色（檢視者／編輯者／核准者）、列表是否顯示進行中人數與各狀態筆數（後端聚合成本）、迴圈旅程的最大圈數限制。
- 畫布函式庫評估已另派 researcher，結果在 `docs/research/canvas-library.md`（完成後由 manager 通知）。

## 追加 2026-10-18

- 追加 2：畫布函式庫評估已提交 `docs/research/canvas-library.md`（develop）。重點：React Flow（@xyflow/react，MIT）可用，回邊與自動排版需自訂；dagre（MIT）簡單；elkjs 授權為 EPL-2.0 OR GPL-3.0-or-later，與 D-008（保留所有權利）相容性需評估，避免 GPL。排版需求取決於追加 1 第 1 點（是否存座標）。請在設計中定選型或列待決定。

## 追加 2026-10-19

- 追加（D-051、D-052，2026-10-19）：產品改為代管、每家客戶一套單一租戶環境。請改版：
  - 移除多租戶：RLS 與相關 role／policy、工作區與 X-Tenant-Id、平台 API、每租戶金鑰（D-019 維持單一金鑰）。資料表的 tenant 欄位是否保留（固定預設值以保留日後回到多租戶的退路）由 architect 決定並寫理由
  - 登入改為單一公司內的使用者登入與角色；帳號開通方式、角色重新提案
  - ProfileStore 預留 D-052 的整合入口（細節由 DESIGN-04 提，這裡只確保不衝突）
  - 拆版、張數重算；撤回的 D-041～D-044 改以新待決定列出；D-045～D-050 若受影響請說明

## 追加 2026-10-19

- 追加（qa FAIL，2026-10-19）：
  1. D-045 已定案 A：auth.md 補登入限速契約（計算維度、門檻、鎖定時間、超限狀態碼與錯誤碼），DESIGN-03 設定表加變數名稱與建議值，`.env.example` 新增該變數，BE-06 派工列授權；刪掉「未定案前不限速」與「D-045～D-050 不受影響」的說法
  2. 待決定改引用 D-055（拆版）、D-056（角色）、D-057（帳號開通）；`CDP_PHONE_DEFAULT_COUNTRY_CODE` 不是決策，只寫變數名稱（值由甲方填 .env）
  3. D-047：設定表補 6 個變數的建議值（lease 必須大於 confirm 逾時），`.env.example` 依現行慣例填入
  4. spec README「多租戶」一節、spec 07（:65、:71、:338）、spec 02 DDL 的 RLS 規範加 D-051 註記（改為「回到多租戶時」或在標題下註明不排期）

## 追加 2026-10-19

- 追加（甲方決定 D-055～D-059，2026-10-19）：D-055 A（拆版）、D-057 A、D-058 A、D-059 A 照建議定案。D-056 甲方修改：三種角色維持（檢視者／編輯者／管理員），不另設核准者，**由管理員兼任審核**；**旅程要經管理員審核才能上線**。請改版：
  1. `journeys.md` §3 生命週期加送審流程：編輯者／管理員送審 → 管理員核准（才建立新版本並上線）或退回（附原因）；送審期間草稿的編輯規則、退回後回到草稿、送審中再改草稿的處理、衝突偵測；狀態列舉、錯誤碼、API（送審、核准、退回、撤回送審）與審核紀錄（誰、何時、原因）一起定
  2. `auth.md` §2 權限矩陣加送審、核准、退回、停止旅程各角色的權限
  3. D-060（待甲方決定，建議 1A 送審者不可核准自己的、2A 每次發佈都要審核含新版、停止不用審核但留紀錄）：先依建議設計，把差異點集中在一處，甲方若選 B 只改那裡
  4. 審核屬 v0.3.0（隨生命週期與畫布，D-055）；v0.2.0 的旅程列表若要顯示「待審核」狀態請說明，DESIGN-03 拆版表與張數一併更新；D-056 的 MCP 引用改為已定案
  5. 其餘內容維持，qa 已 PASS 的部分不要重寫

## 追加 2026-10-19

- 追加（D-060、D-061 定案，2026-10-19）：
  1. D-060 已定案 1A、2A：設計中列為待決定之處改為引用已定案；§3.3 的「若選 B」欄可刪除或縮成一句
  2. D-061 定案：管理金鑰 `POST /journeys` 預設允許並自動寫「管理金鑰建立」審核紀錄（即原建議 A）；另加一個設定變數（名稱由你定，寫進 DESIGN-03 設定表與 `.env.example`，預設值＝允許），設為關閉時管理金鑰建立旅程回 `403`（錯誤碼自訂並列入 journey-engine §5），一律走送審。此限制從哪一版生效、對 demo-seed 與既有測試的影響請說明

## 追加 2026-10-19

- 追加（qa FAIL @c028c34，2026-10-19）：連同上一則 D-060／D-061 追加一起改：
  1. `auth.md:38` 表格壞掉：核准／退回列與 `§4 /api/v1/users*` 列黏在同一行（`||`），補換行
  2. D-060 已定案 1A、2A：journeys.md:118、:120（「建議 A／若選 B」欄）、auth.md:36、:38、DESIGN-03.md:22、:51、spec 07:454 改成「依 D-060」，刪掉 B 備案
  3. D-061 已定案（見上一則）：journeys.md:100、:124、auth.md:35、DESIGN-03.md:51 引用 D-061，不重複選項描述；加開關變數
  4. DESIGN-03.md 拆版段刪除未採 B、C 的張數與「A、B 都…」殘句；「v0.2.0 沒有審核」句與 journeys.md §2 `review_status` 列重複，留一處
  5. auth.md 矩陣：送審、撤回列（含 ADMIN）註明僅 session、Bearer 回 403，或在矩陣下加一行通則
  6. 回報附 `make check` 實際輸出（qa 跑超過 120 秒未看到結果，請用足夠的 timeout）

## 追加 2026-10-19

- 追加（UI-03 需調查，designer @94179ca 轉來；設計稿 `.worktrees/UI-03/docs/ui/journey-platform/index.html`、規格 `docs/ui/journey-platform.md`）：請在契約回答，已涵蓋者回報指出章節即可：
  1. 停止紀錄（`stopped_by`）是否也出現在審核紀錄清單 API；designer 的審核紀錄畫面欄位：動作、版本、操作者 `{user_id, display_name}`、時間、原因
  2. 送審是否帶說明欄；退回原因長度（designer 暫定 1–200 字）
  3. 客戶屬性中哪個鍵可當顯示名稱（姓名不在遮罩白名單時客戶頁標題的處理）
  4. Condition 節點可選的欄位清單（客戶屬性鍵、事件屬性鍵）從哪個 API 取
  5. 前端能否讀到 429 的 `Retry-After`（同源 proxy）
  6. designer 提問「v0.2.0 要不要唯讀旅程頁（版本＋節點圖）」：請估成本並給建議（要不要、併進哪張單），manager 會轉成甲方待決定

## 追加 2026-10-19

## D-062、D-063 定案後修訂

1. D-062 定案（加唯讀旅程頁，併進 FE-03＋QA-05a）：「待決定」段改為引用 D-062，派工表 FE-03／QA-05a 範圍寫入唯讀旅程頁
2. D-063 定案（v0.2.0 移除 tenant_id）：
   - 做法 #1 改寫：所有表不帶 `tenant_id`（含 v0.2.0 新表 profile、profile_identifier、journey），刪介面表例外段；廢除 `CDP_TENANT_ID`（設定表、`.env.example`）
   - 派工表加一張後端重構單（REFACTOR-01，新 migration 拿掉 7 張表的欄位、重建 event PK 與索引、清程式與 spec 04 介面）＋配套 qa 單，排在 BE-05 前；BE-06 以後的 migration 版號順延；第一版 12→14 張
   - spec 01～07、README、契約（journey-engine、auth 等）一併清掉 tenant_id；RLS 段改寫為「回到多租戶時的做法」，註明須加回欄位並回填
   - 風險表、已定案影響列更新
3. 分支先與 develop 同步再修（qa 建議，避免 PR diff 誤顯示）
