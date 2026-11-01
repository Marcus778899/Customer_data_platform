PASS 51d0263

# 驗證 plain-ux-ui（PR #162，docs，第 3 輪）

## 範圍
- `git diff --name-only develop...HEAD`：5 個檔案（MASTER.md、enrollment-trace.md、enrollment-trace/plain.html、journey-platform.md、journey-platform/index.html），全在負責檔案內；工作樹乾淨。

## 甲方意見逐項
1. 麵包屑錯位：兩份設計稿 `.crumbs ol`、`li` 皆 `display:flex; align-items:center`，`li` 與 `a` 同為 `min-height:32px`（<640px 為 touch-target 44px），共用垂直中線。分隔符是 `li + li::before { content:"/" / "" }`，不在 DOM（grep 無 `<span>/</span>`、sep 類別），讀屏不唸。最後一段是純文字 `li[aria-current="page"]`。已看 1440px 截圖（plain-ux-ui-1440-plain-pending.png）：連結、斜線、「旅程進度」同一中線，對齊。
2. 規格寫明麵包屑規則：`enrollment-trace.md`「麵包屑（全站，frontend 照做）」有結構、flex、高度、gap；MASTER.md 第 18 行同步。通過。
3. 四個寬度截圖：.agent/reports 有 1440、1280、500、375 的截圖（plain-ux-ui-1440-*、1280-*、500-*、375-*）。我只目視 1440 一張，其餘沒逐張看；未重跑 playwright。
4. 用詞與 D-079～D-081：`journey-platform/index.html` 的「Enrollment」出現 0 次、「Enrollment ID」0 次；plain.html 的 3 次「Enrollment」都在改版前後對照與說明（原本→改成），StartNode 只在術語對照表「不顯示」列；ID 收進「進階資訊」（plain.html 12 處提到）。規格 `enrollment-trace.md` 寫明 D-079～D-081 已定案、無待決定。通過。

## 規格與設計稿一致
- 上輪問題已修：兩份設計稿 1024px 以下 `.top` 皆 `position: sticky; top: 0; z-index: 4`，與 journey-platform.md 第 46、53 行一致。
- `.shell`、`.side`、`.page` 規則上輪已核對，本輪沒被改動。

## 檢查沒被弄壞
- 在 worktree 跑 `make lint`：`eslint . && tsc --noEmit` 無錯誤輸出。docs 單，未跑 make test。

## 限制（不列為 FAIL）
- 本環境無 playwright，自動量測與其餘截圖沒獨立重做；依 CSS 靜態檢查與一張截圖判斷。
- 窄螢幕右側留白的真因仍未重現；frontend 實作後須在真實頁面 500px 再確認（已在 open_questions）。

問題數量：0
