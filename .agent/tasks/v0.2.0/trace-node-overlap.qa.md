PASS 602e9ab

# QA trace-node-overlap（fast，PR #151，uat F-1）

## 執行
- `make check`（在 .worktrees/trace-node-overlap，自己跑）：BUILD SUCCESS；vitest `Test Files 17 passed (17)  Tests 200 passed (200)`；`check passed`，exit 0。

## 驗收條件
1. 三態不重疊：結構由 `FlowGraph.tsx` 確認：狀態文字移進 `.n-head`（分類＋狀態同列，flex、`white-space:nowrap`、`.n-state flex-shrink:0`、`.n-cat` 可截斷），卡片直向只剩 3 列（標頭、名稱、類型）；名稱與類型維持 ellipsis。PASS（結構層）。
2. 測試：`FlowGraph.test.tsx` 對 visited／current／unvisited 各驗分類、名稱（長名稱）、類型、狀態文字、aria-label，並斷言 `.node.children` 為 5（2 Handle＋3 列）與 `.n-head` 含狀態文字。對照原始碼（分類、名稱、類型、狀態各自一列，children 為 6，且無 `.n-head`），此測試在修正前會失敗，能抓到「多一行」的原問題。PASS。
3. `make check` 通過。PASS。

## 估算（CSS 高度，非瀏覽器量測）
border-box，NODE_H=72，padding 上下各 4px：可用高度 unvisited 62、visited 60、current 58。三列內容約 head 19.2（line-height 1.6）＋name 22.4＋type 約 15（`font:` 簡寫重設 line-height）＋gap 4 ≈ 61。visited／current 在算術上超出約 1～3px，超出部分是行距（leading）而非字形，且 `justify-content:center`＋`overflow:hidden` 上下對稱裁切，預期不會蓋到文字；原本多一行（約 +20px）才是真的重疊。

## 風險／不阻擋（供 uat 複驗）
- 測試只驗 DOM 結構，不驗像素；「尺寸常數變更時排版測試仍通過」一項的 `尺寸常數與排版` 測試只斷言 NODE_W／NODE_H > 0，實質上無排版檢查（jsdom 無版面計算，屬環境限制）。
- 當前位置（3px 框）三列可能貼近上下邊；請 uat 依 D-069 於瀏覽器複驗 s09、s10、s12，特別看 current 態與長名稱。若仍有裁切，建議加大 `--node-height`（需動 tokens.css，超出本單範圍，另開單）。
- 未發現寫死設定值（port、網址、token）；本單僅動 FlowGraph.tsx、app.css、測試檔，皆在負責檔案內。

問題數量：0（FAIL 項）
