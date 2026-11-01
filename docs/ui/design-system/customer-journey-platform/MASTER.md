# 設計系統：客戶旅程平台（MASTER）

下次做 UI 先讀這份；token 在 `web/src/styles/tokens.css`（唯一來源，設計稿與前端共用），這裡只記規則。

## 來源與取捨
- 2026-11-01 plain-ux-ui 用 ui-ux-pro-max 查過（`--design-system`、ux、icons）。它推薦的 Dark OLED＋Fira 與 D-017（中性藍灰、中性書面語）和使用者（行銷、業務、客服，白天辦公）不合，不採用；沿用既有 token。
- 採用的建議：狀態一律文字＋顏色＋圖示、空狀態要有說明、進度用步驟指示、SVG 圖示（Lucide 線條風格，inline，`aria-hidden`）、尊重 reduced-motion。

## 規則
- 風格：中性藍灰、扁平、白底卡片（1px 邊框，圓角 `--radius-md`）；不用陰影堆疊，彈出層才用 `--shadow-popover`。
- 字體：系統字型堆疊（含 Noto Sans TC／微軟正黑）；等寬字型只給 ID 與技術值。字級 `--text-xs`～`--text-xl`；摘要答案用 `--text-lg` 加粗，問題用 `--text-sm` 灰色。
- 色彩語意：info 藍＝進行中／待送、success 綠＝已完成／已送出、warning 琥珀＝等待中／目前位置、danger 紅＝失敗、neutral 灰＝中止／未走到。文字 >= 4.5:1，邊框與圖形 >= 3:1。
- 外框：側欄固定整個視窗高度（sticky），內容區自然延伸；沒有外框線、圓角、body 留白；1024px 以下改頂列（sticky）＋選單。
- 版面：內容欄 `max-width` 960px（軌跡、表單）或 `--content-wide`（列表、圖）；`.page` 要有 `min-width: 0` 與 `overflow-wrap: anywhere`，避免長字串撐出橫向捲動。
- 觸控：640px 以下互動元件高 >= 44px；表格轉卡片（`td` 用 `data-l` 顯示欄名）。
- 語言：面向非工程人員的畫面不出現節點型別名、ISO 時間、ID（ID 只在「進階資訊」與客戶頁）；術語對照見 `docs/ui/enrollment-trace.md`。
- 動效：只有骨架脈動與按鈕 150ms 顏色轉場，reduced-motion 下關閉。
- 麵包屑：`ol`、`li` 皆 flex＋`align-items: center`；連結、分隔符、目前頁（`aria-current="page"`）同一垂直中線；分隔符用 CSS `content: "/" / ""`，讀屏不唸；觸控 44px。細則見 `docs/ui/enrollment-trace.md`「麵包屑」
- 窄螢幕 `dl` 單欄時 `dd` 下方要有間距（`--space-2`），避免下一個標籤貼著上一個值
