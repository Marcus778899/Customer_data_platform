# 派工單 app-shell-fill

- **類型**：fix
- **負責**：frontend
- **目標**：甲方驗收回饋：外框撐滿視窗（桌面側欄下方不再留白、不再呈現浮動卡片）、窄螢幕外框填滿寬度無橫向捲動、全站麵包屑對齊
- **背景**：甲方截圖（桌面側欄下方大片空白；500px 旅程列表外框沒填滿、右側留白）；設計 plain-ux-ui（PR #162，甲方已核可）找到原因：`.shell` 是有邊框、圓角、`min-height: 640px` 的卡片，body 另留 padding；麵包屑連結 `inline-flex; min-height` 比純文字段高，未共用中線
- **設計依據**：`docs/ui/journey-platform.md`（外框規則、受影響頁面、派工順序第 1 批）、`docs/ui/enrollment-trace.md`「麵包屑」、`docs/ui/design-system/customer-journey-platform/MASTER.md`；設計稿 `docs/ui/journey-platform/index.html`
- **相依**：無（與 plain-copy-glossary 可平行）
- **負責檔案**：
  - `web/src/styles/app.css`（外框、側欄、頂列、`.page`、body、登入頁容器、麵包屑、`dl` 窄螢幕間距；不動軌跡頁專屬段落）
  - `web/src/components/Shell.tsx`（含麵包屑結構）
  - `web/src/pages/LoginPage.tsx`（只調容器以配合 body 不留白後仍置中）
  - `web/src/components/` 底下對應的單元測試
- **禁區**：`web/src/styles/tokens.css`、`format.ts`、各頁內容與文案不改；`web/tests/acceptance/` 由 qa 負責；不可讀 .env
- **驗收測試**：
  - `web/tests/acceptance/app-shell-fill/`（jsdom 可驗的部分：麵包屑 `nav[aria-label=麵包屑] > ol > li` 結構、最後一段 `aria-current="page"` 且非連結、分隔符不在 DOM 文字中；外框不再套用卡片樣式可由 CSS 規則檢查；版面像素由階段 uat 實測，D-069）
  - 受影響的既有驗收測試：`web/tests/acceptance/shell/`、`web/tests/acceptance/narrow-layout/`（若與新規則衝突，qa「A」依新規格改寫後一起 lock）
- **驗收條件**：
  - [ ] 上列驗收測試全綠
  - [ ] 桌面：側欄與內容撐滿視窗高度（`min-height: 100dvh`），側欄 sticky；無外框線與圓角；body 不留白
  - [ ] 窄螢幕（375px、500px）：外框填滿寬度、頂列 sticky、無橫向捲動；`.page` `min-width: 0`
  - [ ] 麵包屑照規格對齊；登入頁仍置中
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/app-shell-fill.json`

## 執行資訊（ae task 產生）

- Issue：#164
- 分支：`fix/app-shell-fill`（從 develop 開出）
- 工作目錄：`.worktrees\app-shell-fill`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`

## 追加 2026-11-01

### .agent/reports/app-shell-fill.review.md

REQUEST_CHANGES d5e28bc

## 檢查範圍
- `gh pr diff 167`（4 檔：`web/src/components/Shell.tsx`、`web/src/styles/app.css`、qa 的 `web/tests/acceptance/app-shell-fill/`、`narrow-layout` 改寫一行），對照派工單 `.agent/tasks/app-shell-fill.md`、`docs/ui/journey-platform.md`「外框撐滿視窗」、`docs/ui/enrollment-trace.md`「麵包屑」
- 負責檔案：只改 `Shell.tsx`、`app.css`；`tokens.css`、`format.ts`、各頁、`LoginPage.tsx` 未動；驗收測試由 qa commit（91eea5f），worker commit（d5e28bc）沒碰測試。符合
- 寫死值：新增只有 `32px`／`44px`（規格指定的麵包屑高度）、`100dvh`、`z-index: 4`，沒有顏色、port、網址
- CI：Changes／Check／Security 全部 pass

## 對照規格
| 規則 | 結果 |
|---|---|
| `body` 無 padding（含 640px 以下） | app.css:3、移除 640px 的 body padding。符合 |
| `.shell` 無外框線與圓角、`min-height: 100dvh` | app.css:105。符合 |
| `.side` sticky、top 0、`height: 100dvh`、`overflow-y: auto` | app.css:108，同時拿掉圓角。符合 |
| `.top` sticky（1024px 以下） | app.css:127 `position: sticky; top: 0; z-index: 4`；sticky 也讓 `.drop` 的 `absolute; top: 100%` 有定位基準，`.skip:focus` z-index 5 仍在上層。符合 |
| `.page` `min-width: 0`、`overflow-wrap: anywhere` | app.css:120。符合 |
| 登入頁仍置中 | `.login-wrap` 是 grid＋`place-items: center`，有自己的 padding，`min-height` 改成 100dvh。符合 |
| 麵包屑 `nav > ol > li`，最後一段 `aria-current="page"` 且純文字，前面各段是連結 | Shell.tsx:89-100。符合（沒有 `to` 的中間段仍是純文字、不加 aria-current，合理） |
| `ol`／`li` flex＋`align-items: center`、li 最小高 32px（640px 以下 44px）、`line-height: 1.4`、`gap: 0 var(--space-2)` | app.css:100-103、207。符合 |
| 分隔符 `content: "/" / ""`，前面先寫 `content: "/"` 給不支援的瀏覽器 | app.css:102。符合 |

## 軌跡頁專屬段落（app.css:21-77，v0.1.0／enrollment-trace）
- 只改兩處：第 3 行 `body`、640px 區塊移除 `body { padding }`。兩處都在派工單列出的範圍（body）內，其他軌跡頁規則（`table.stack`、`dl.kv`、`.mask-note`、`.tz-note`…）都沒動。符合
- 第 203-214 行（窄螢幕補充，含 `.graph-note`）只改了麵包屑那一行

## 受影響頁面
- 走 `Shell` 的頁面（旅程列表、旅程頁、客戶搜尋、客戶頁、旅程進度清單與頁、受眾、無權限、修改密碼、404）：受影響的規則和規格列的一致；這些頁面都會用到 `Crumbs`（AudiencePage、CustomerPage、CustomerSearchPage、EnrollmentBrowsePage、ForbiddenPage、JourneyListPage、JourneyPage、PasswordPage、TracePage），新結構不需要各頁配合
- 登入頁：已確認，見上表
- **規格沒列出、但被影響的畫面**：`App.tsx` 有兩個畫面不在 Shell 也不在 `.login-wrap` 裡，而是用 `.app` 包起來（見問題 1）

## 問題
1. `web/src/styles/app.css:4` — 這個 PR 拿掉 `body` padding 後，`.app` 自己沒有 padding，所以 `web/src/App.tsx:137`（`auth.status === 'checking'`，「載入中…」）和 `web/src/App.tsx:144`（`auth.status === 'error'`，「無法確認登入狀態」的 ErrorBox 和重試鈕）會直接貼齊視窗左、上、右邊。原本有 24px 留白（640px 以下 16px），這是這次改動造成的退步。規格說「登入頁自己的容器加」留白，同樣的處理也該套用到 `.app` — 要怎麼改：在 `.app` 加上 `padding: var(--space-5)`，並在 640px 以下的區塊加 `.app { padding: var(--space-4); }`，維持原本的視覺；檔案在負責範圍內，不影響現有驗收測試（測試只檢查 `body`）

## 非阻擋備註（不用在這張單改）
- `.login-wrap`、`.shell`、`.side` 只寫 `100dvh`，沒有 `100vh` 備援。在不支援 dvh 的舊瀏覽器（Safari < 15.4、Chrome < 108），這幾條宣告整條無效：`.side` 會失去固定高度，登入頁不會垂直置中。要相容的話可以比照麵包屑，先寫一行 `100vh` 再寫 `100dvh`，由 worker 自己決定
- `.page` 的 `overflow-wrap: anywhere` 會繼承到表格儲存格，自動排版的表格裡，長的英數 ID 可能被斷在字中間。這是規格指定的做法，像素結果留給階段 uat 確認（D-069）
- 測試：qa 的驗收測試確實測到驗收條件中 jsdom 能驗的部分（CSS 規則解析＋DOM 結構，包含 375／500／640 的斷點）；像素部分依 D-069 交給 uat
