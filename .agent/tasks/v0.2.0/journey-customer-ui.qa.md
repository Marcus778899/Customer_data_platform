PASS 08b0c89

# journey-customer-ui 驗證（B，依 D-069：自動測試、lint、build、靜態檢查）

HEAD 08b0c89（已含最新 develop 合併）。獨立重跑，未沿用上一輪結論。

## 指令與輸出（在 .worktrees/journey-customer-ui）

- `npm run lint -w web`：`eslint . && tsc --noEmit` 無錯誤、exit 0
- `cd web && npx vitest run`：
  ```
  Test Files  16 passed (16)
       Tests  196 passed (196)
  ```
  含 web/tests/acceptance/journey-customer-ui/ 全部（journeys、customer-search、customer-page、trace-graph、enrollment-browse、narrow-a11y）與既有 v0.1.0／app-shell-login 測試。
- `npm run build -w web`：vite build 成功（只有 chunk 大於 500 kB 警告，不影響）。
- 後端 mvn 測試需 Docker；本單未動 api/，未跑。

## 靜態檢查

- 驗收測試鎖定：lock 後無測試檔變更（`git log develop..HEAD -- web/tests` 只有 qa 的兩個 test commit）；tokens.css、api/、docs/ 與 develop 無差異（禁區未動）。
- 新相依只有 `@xyflow/react` 12.12.0、`@dagrejs/dagre` 3.1.1（web/package.json）。
- 無寫死 port／網址／金鑰（grep 僅見 X-XSRF-TOKEN 標頭名稱）。
- 抽查驗收條件對應實作：列表無人數欄；旅程頁「不是最新版本」「找不到這個旅程」；搜尋 `matched_by` 經 navigate state 傳遞、表格 caption 最多 20 筆；客戶頁遮罩只依 `masked`／`masked_paths`；軌跡 `<ol aria-label="節點軌跡，由舊到新">` 與 `aria-current="step"`；路由與 routes.md 一致（`/` 保留查詢頁、`/enrollments` 瀏覽頁、`/enrollments/:id` 取代 DetailPage）。

## 條件對照

各驗收條件由上列驗收測試（全綠）覆蓋；「窄螢幕與無障礙」的結構／語意部分由 narrow-a11y.test 驗證，視覺部分見下。

## 留給 uat 的瀏覽器實測項目（D-069，不列 FAIL）

1. 375px、800px 版面：旅程列表、旅程頁、客戶搜尋表格（data-label 卡片化）、客戶頁兩欄折疊、軌跡頁
2. 色彩對比（含節點三態、徽章、焦點環）與 prefers-reduced-motion
3. 節點圖畫法：dagre 左到右排版、連線 handles 位置、三態樣式、窄螢幕可捲動／縮放；回邊自訂邊元件（v0.3.0）
4. React Flow 右下歸屬連結是否隱藏（worker open_question，需甲方／manager 決定）
5. 軌跡頁無「複製 Enrollment ID」鈕（設計稿有、既有遮罩測試禁止；需 designer／qa 決定）
6. 接上真後端後：遮罩、cursor 載入更多、單筆直進客戶頁、401/403/404 的實際行為與 request_id 顯示
