# 旅程畫布函式庫評估（v0.2.0，研究日 2026-10-19）
**結論（事實整理，非決策）**
1. D-018 原定 React Flow（@xyflow/react，MIT，12.12.0）：內建節點鍵盤操作；自動排版與回邊要自訂或搭配排版庫。
2. 排版候選：dagre（MIT，3.1.1）較簡單、靜態；elkjs 功能完整，授權為「EPL-2.0 OR GPL-3.0-or-later」，與 LICENSE「保留所有權利」（D-008）的相容性需甲方／architect 確認。
3. 排版是否需要取決於節點是否存座標（UI-03 需調查 1）；此題未定前無法選定排版庫。

| 套件 | 授權（原文） | 版本／日期 | 維護 | 付費限制 |
|---|---|---|---|---|
| @xyflow/react | "MIT" [1] | 12.12.0；GitHub 頁 "24 Sep"（年份未顯示，推定 2026）[6] | 有 GitHub release 頁 [6] | Pro：範例與模板、優先 issue、支援、席次；Starter $169／月、Professional $289／月、Enterprise 需報價 [2] |
| dagre | "MIT"（npm）[7] | 3.1.1，2026-08-08 [7][9] | 2026-08-08 有 commit [9] | 無 |
| elkjs | "EPL-2.0 OR GPL-3.0-or-later" [10] | 0.12.0，2026-07-17 [10] | 2026-10-06 有 commit [13] | 無（授權條件見左） |
| @joint/core（JointJS） | "MPL-2.0" [15] | 4.3.3；日期查不到 | 查不到 | JointJS+ 商業授權，"Licensed per developer"，"JointJS+ must be part of a larger system" [14] |
| cytoscape | "MIT"（LICENSE 檔）[16][17] | 3.34.4；日期查不到 | 查不到 | 無 |
| rete | "MIT" [18] | 2.0.6；日期查不到 | 查不到 | 無 |

| 套件 | 鍵盤／無障礙 | 自動排版 | 回邊（cycle）處理 | 包體（npm unpacked，非 gzip） |
|---|---|---|---|---|
| @xyflow/react | Tab 巡覽、Enter/Space 選取、Esc 清除、方向鍵移動（需 nodesDraggable＋nodesFocusable，Shift 加速）；`disableKeyboardA11y`；`ariaLabelConfig` 可在地化；`ariaRole` 可改節點角色 [3] | 無內建；dagre／elkjs 範例（範例為 Pro）[5][19] | 不處理；需自訂邊 | 1,216,196 B [1] |
| dagre | 無 UI，不適用 | 靜態；官方："If the nodes or edges in the graph change, the layout won't recalculate!" [4] | `acyclicer: greedy` 移除 feedback arc set；官方文件只列此選項 [8] | 1,413,014 B [7] |
| elkjs | 無 UI，不適用 | 完整；可設定，但"most complicated" [19] | 預設 GREEDY；反向邊"will point left if edges usually point right"，另有 DEPTH_FIRST 等 9 種 [11] | 8,046,232 B [10]（含 worker 檔） |
| JointJS | 查無官方鍵盤導覽；部落格稱可對 SVG 加 ARIA 與 tabindex [21] | 查不到 | 查不到 | 6,233,325 B [15] |
| Cytoscape.js | 查不到官方無障礙說明（[23] 未提及） | 查不到（需看 extension） | 查不到 | 5,704,386 B [16] |
| Rete.js | 查不到 | 查不到 | 查不到 | 225,942 B [18]（定位為節點編輯器，未查證是否適合旅程圖） |

**React 19**：xyflow 宣告 peerDependencies `react: ">=17"`（含 19），官方未見明確 React 19 測試聲明 [1]；dagre、elkjs 無 React 相依 [7][10]；其餘框架無關，React 包裝查不到。**包體**：bundlephobia 查不到 minified／gzip；上表為 npm unpacked，非打包後大小。
**Pro 補充**：核心 "React Flow is open-source MIT-licensed software" [2]；auto-layout 範例 "This is a Pro example."，授權標示 "xyflow Pro License" [5]；Pro 授權條文查不到（[2] 的授權頁 404）。

**建議組合（建議，供 architect 決定）**
- 建議：@xyflow/react（MIT）＋dagre（MIT）；理由：授權單純、包小、靜態左到右排版足夠；若節點需動態重排或要存座標，再評估 elkjs。
- 建議：elkjs 若採用，先由法務／甲方確認採 EPL-2.0 或 GPL-3.0 的條件，再決定。
**需注意的坑**
- 回邊：查不到 React Flow、dagre、elkjs 提供點狀回邊樣式；需自訂 edge 元件（UI-03 規格要求）。
- 鍵盤：design 規定卡片為 `<button>`＋aria-pressed，React Flow 預設節點為 role="group"，需以 `ariaRole` 或自訂節點覆寫 [3]。
- 查不到：React Flow 的鍵盤縮放（+/−/0）、鍵盤連線、小地圖搜尋是否內建，需實測。
- 排版為非同步（elk 需 hook）[19]；動態重排需重算。

來源：[1] https://registry.npmjs.org/@xyflow/react/latest [2] https://reactflow.dev/pro [3] https://reactflow.dev/learn/advanced-use/accessibility [4] https://reactflow.dev/examples/layout/dagre [5] https://reactflow.dev/examples/layout/auto-layout [6] https://github.com/xyflow/xyflow/releases/tag/%40xyflow%2Freact%4012.12.0 [7] https://registry.npmjs.org/@dagrejs/dagre [8] https://github.com/dagrejs/dagre/wiki [9] https://api.github.com/repos/dagrejs/dagre/commits?per_page=1 [10] https://registry.npmjs.org/elkjs [11] https://eclipse.dev/elk/reference/options/org-eclipse-elk-layered-cycleBreaking-strategy.html [13] https://api.github.com/repos/kieler/elkjs/commits?per_page=1 [14] https://www.jointjs.com/license [15] https://registry.npmjs.org/@joint/core/latest [16] https://registry.npmjs.org/cytoscape/latest [17] https://github.com/cytoscape/cytoscape.js/blob/unstable/LICENSE [18] https://registry.npmjs.org/rete/latest [19] https://reactflow.dev/learn/layouting/layouting [21] https://jointjs.com/blog/jointjs-vs-gojs [23] https://js.cytoscape.org/（註：[2][5][14] 條文為 WebFetch 摘要，未逐字核對；[21][23] 為搜尋結果，非官方文件）
