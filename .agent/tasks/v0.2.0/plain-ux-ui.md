# 派工單 plain-ux-ui

- **類型**：docs
- **負責**：designer
- **目標**：甲方驗收回饋（2026-11-01）：修外框與窄螢幕版面，並提出讓行銷、業務等非工程人員不需教育訓練就看得懂的軌跡頁改版方案
- **背景**：甲方親手驗收 v0.2.0 時提出（截圖在 `C:/Users/Marcus/AppData/Local/Temp/claude/D--workplace-CustomerDataPlatform/924dc974-dfe2-4e67-94b6-e97e6a580b8b/images/1.png`、`3.png`）：
  1. 桌面版：整個外框（側欄＋內容）像一張卡片，只到內容高度（約 650px）就結束，側欄下方與畫面底部之間大片空白，很奇怪
  2. 手機版（約 500px 寬，旅程列表頁）：外框卡片沒有填滿寬度，右側留白、出現分隔捲軸，排版爆掉
  3. 軌跡頁（例 `/enrollments/01M6BC7Y684YR2W4QQJDHH1RGJ`）：工程師看得懂，但產品要給行銷、業務團隊用，目前的內容（Enrollment、節點類型名如 StartNode、ID、UTC 字串、技術用語）需要大量教育訓練。甲方要改善意見
  產品方向：平台未來主要給非工程人員使用（另見 issue #157 成效分析）
- **設計依據**：`docs/ui/journey-platform.md`、`docs/ui/enrollment-trace.md` 與設計稿；D-017（中性藍灰、中性書面語）、D-049（暫名與導覽）、D-014／D-021（遮罩）
- **相依**：無
- **負責檔案**：
  - `docs/ui/journey-platform.md`、`docs/ui/journey-platform/`
  - `docs/ui/enrollment-trace.md`、`docs/ui/enrollment-trace/`
- **禁區**：不寫前端程式；不改 API 契約（需要後端新增欄位就列進「待決定」或「需調查」，寫明理由）；不可讀 .env
- **驗收測試**：無（docs）
- **驗收條件**：
  - [ ] 使用 ui-ux-pro-max skill 協助檢視與設計（甲方指定）
  - [ ] 外框：桌面版側欄與內容區撐滿視窗高度、不再呈現浮動卡片與底部空白；窄螢幕（375px、500px）外框填滿寬度、無橫向捲動；更新規格與設計稿，並列出受影響頁面
  - [ ] 軌跡頁改版方案：以行銷／業務人員的語言重寫節點、狀態、時間、ID 的呈現方式；附術語對照表；首屏用白話摘要回答「這位客戶目前在哪、接下來會發生什麼、為什麼沒收到訊息」；設計稿呈現改版前後對照；若有多個方向，列 2～3 個選項與建議
  - [ ] 改版若涉及範圍或品牌語氣（例：Enrollment 要改稱什麼、全站術語改名、是否隱藏 ID）寫進「待決定」；其餘細節寫進「自行決定」表
  - [ ] 「派工順序」：列出給 frontend 的實作單（task_id 用 `<功能>-<動作>`），標可否平行
- **驗證指令**：用 playwright 開設計稿，1280px、500px、375px 截圖附在回報
- **回報檔**：`.agent/reports/plain-ux-ui.json`

## 執行資訊（ae task 產生）

- Issue：#158
- 分支：`docs/plain-ux-ui`（從 develop 開出）
- 工作目錄：`.worktrees\plain-ux-ui`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-11-01

- **負責檔案**：補 `docs/ui/design-system/customer-journey-platform/MASTER.md`（ui-ux-pro-max skill 的設計系統紀錄，甲方指定使用該 skill）

## 追加 2026-11-01

### .agent/reports/plain-ux-ui.qa.md

FAIL 1fcc2df

# 驗證 plain-ux-ui（PR #162，docs）

## 範圍
- `git diff --stat develop...HEAD`：只有 5 個檔案，皆在負責檔案（含追加的 MASTER.md）：enrollment-trace.md、enrollment-trace/plain.html、journey-platform.md、journey-platform/index.html、design-system/customer-journey-platform/MASTER.md。無越界。
- 回報檔在 `.agent/reports/`，符合規定。

## 檢查
- 在 worktree 跑 `make lint`：eslint . && tsc --noEmit 無錯誤，退出正常（docs 變更未影響程式）。
- 未跑 `make test`：docs 單，沒有改程式與測試。
- 未重做 playwright 截圖或量測：本環境找不到 playwright（node_modules/.bin 沒有）。回報自述「以截圖目視確認，未跑自動化量測」。我只做了 CSS 靜態檢查：index.html 的 `.shell` 已 min-height:100dvh、無邊框圓角，`.side` sticky + 100dvh，`.page` 有 min-width:0 與 overflow-wrap:anywhere，body 無 padding。

## 驗收條件對照
- ui-ux-pro-max：MASTER.md「來源與取捨」記載使用與取捨。PASS（無法證實實際執行，只有文字記載）
- 外框撐滿＋窄螢幕規則＋受影響頁面：journey-platform.md「外框撐滿視窗」節有問題、規則、受影響頁面；index.html 已同步。部分不一致，見問題 1。
- 軌跡頁方案（A/B/C 與建議）、白話摘要三問、術語對照表（plain.html#glossary）、時間規則、改版前後對照（#compare）、15 個狀態：存在。PASS
- 待決定（3 項）與自行決定表：enrollment-trace.md 有，journey-platform.md 引用。PASS
- 派工順序（app-shell-fill、plain-copy-glossary、trace-plain-page、trace-plain-list，標平行與依賴）：journey-platform.md 有。PASS

## 問題（1）
1. 規格與設計稿不一致：`journey-platform.md` 外框規則、窄螢幕段與 MASTER.md 都寫「`.top` sticky」（<1024px 頂列固定），但 `docs/ui/journey-platform/index.html` 的 `@media (max-width: 1024px)` 內 `.top` 仍是 `position: relative`（只有 `enrollment-trace/plain.html` 的 `.top` 是 sticky: 第 142 行）。frontend 若照設計稿做就違反規格。
   重現：`git -C .worktrees/plain-ux-ui grep -n "\.top {" docs/ui/journey-platform/index.html`，看第二筆含 `position: relative`；對照 `journey-platform.md`「1024px 以下側欄改頂列（sticky）」。
   修法：index.html 的 `.top` 改 `position: sticky; top: 0; z-index: 4`（與 plain.html 一致）。

## 備註（不列為 FAIL）
- 回報自述 500px 右側留白的真因未重現；frontend 實作後須在真實頁面 500px 確認（已在 open_questions）。

## 追加 2026-11-01

## 甲方意見（2026-11-01，看設計稿後）

- 甲方用瀏覽器打開設計稿 `.html`，排版仍有「掉漆」，桌面版與手機版都有。截圖：`C:/Users/Marcus/AppData/Local/Temp/claude/D--workplace-CustomerDataPlatform/924dc974-dfe2-4e67-94b6-e97e6a580b8b/images/4.png`
  - 麵包屑「客戶 / 陳＊＊ / 旅程進度」：斜線分隔符與最後一段「旅程進度」的基線比前面的連結高，上下錯位、看起來沒對齊（uat F-6 也提過麵包屑斜線；甲方先前手機截圖 `images/3.png` 的「旅程 / 旅程清單」同樣錯位）
- 要求：
  1. 修正麵包屑：連結、分隔符、目前頁三者垂直置中、同一基線；分隔符 `aria-hidden`，不被讀屏唸出
  2. 用 ui-ux-pro-max skill 把兩份設計稿（`enrollment-trace/plain.html`、`journey-platform/index.html`）在 1440px、1280px、500px、375px 全面逐區檢查類似的對齊、間距、換行、截斷問題並一併修掉；回報附這四個寬度的截圖，列出每個修正項目
  3. 規格裡寫明麵包屑的版面規則，讓 frontend 照做
- D-079～D-081 已定案（皆 A），設計稿與規格的用詞照此統一（「旅程進度」、ID 收進「進階資訊」、方案 A）
