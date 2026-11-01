# 派工單 trace-node-overlap

- **類型**：fix
- **負責**：frontend
- **通道**：fast
- **目標**：v0.2.0 uat F-1（major）：圖形軌跡節點卡片在「已經過／目前位置」時，狀態標籤蓋住節點名稱與類型；修成三態都能完整顯示分類、名稱、類型與狀態標示
- **背景**：`docs/uat/v0.2.0.md` F-1；截圖 `docs/uat/v0.2.0/s09-trace-completed.png`、`s10-trace-graph-zoom.png`、`s12-trace-waiting.png`。卡片約 70px 高，多一行 `n-state` 就擠壓重疊；未經過節點正常
- **設計依據**：`docs/ui/journey-platform.md`「節點三態」（只加框與標示，內容不變）、「窄螢幕與無障礙」（對比、鍵盤、讀屏）；設計稿 `docs/ui/journey-platform/`
- **相依**：無
- **負責檔案**：
  - `web/src/graph/FlowGraph.tsx`、`web/src/graph/model.ts`（如需調整 `NODE_H` 等尺寸）
  - `web/src/styles/app.css`（只改圖節點相關樣式）
  - `web/src/graph/` 底下新增或修改的測試檔
- **禁區**：不改 API、契約、其他頁面；`web/src/styles/tokens.css` 不改；不可讀 .env
- **驗收測試**：無（fast；worker 自己補測試）
- **驗收條件**：
  - [ ] 已經過、目前位置、未經過三態的節點卡片，分類、名稱、類型、狀態標示都完整可見、不重疊（節點名稱較長時也一樣，可截斷加省略號但不被蓋住）
  - [ ] 補測試：三態節點都渲染出名稱、類型與對應狀態文字；尺寸常數變更時連線與排版測試仍通過
  - [ ] `make check` 通過
- **驗證指令**：`make check`
- **回報檔**：`.agent/reports/trace-node-overlap.json`

## 執行資訊（ae task 產生）

- Issue：#143
- 分支：`fix/trace-node-overlap`（從 develop 開出）
- 工作目錄：`.worktrees\trace-node-overlap`；所有修改、指令、commit 都在這裡做，回報檔寫在主目錄 `.agent/reports/`
