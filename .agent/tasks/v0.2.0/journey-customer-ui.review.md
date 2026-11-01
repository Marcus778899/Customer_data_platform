APPROVE 08b0c89

# PR #98 journey-customer-ui code review（architect）

依據：派工單（含 2026-10-21 追加）、`reports/journey-customer-ui.routes.md`、`journeys.md` §1–§2、`profiles.md` §1、§3、`journey-engine.md` §4、§4.4、§4.5、DESIGN-04 §5、`docs/ui/journey-platform.md`。瀏覽器實測依 D-069 交給 uat，不在本次範圍。測試結果引用 qa 證據（PASS @ 08b0c89：lint、vitest 16 檔 196 項、build）。

## 檢查項目

| 項目 | 結果 | 依據 |
|---|---|---|
| 型別對照契約 | 一致 | `api/types.ts`：`JourneySummary`／`JourneyDetail`（含 `has_draft`、`review_status`、`stopped_*`）、`JourneyVersion`（`layout` 可為 null）、`ProfileSummary`／`ProfileDetail`、`Identifier.masked` 欄位名與 nullable 都和 journeys.md §1、profiles.md §1 一樣；沒有契約以外的欄位 |
| API 路徑與參數 | 一致 | `client.ts`：`/journeys`（q、status、cursor、limit）、`/journeys/{id}`、`/journeys/{id}/versions/{v}`、`/profiles/search?q`、`/profiles/{id}`、`/profiles/{id}/enrollments`、`/enrollments`（q 選填＋`journey_id`）。`qs` 會丟掉空字串，所以瀏覽頁沒有篩選時不會送出 `status=` |
| 接 BE-07 時要不要改介面 | 不用 | 前端只用契約欄位；qa fixture（`fixtures.tsx`）的 builder 欄位就是契約欄位。`listProfileEnrollments` 還沒有 `status`／`limit` 參數，之後要用再加選填參數，不影響現有呼叫 |
| §4.5 走過的節點與連線 | 正確 | `graph/model.ts` `deriveOverlay`：visited = `trace[].node_id` 加上 `entry_node`；連線 = `outputs[out_port]`，而且必須等於 `trace[i+1].node_id` 才算走過（對不上就不畫）；`entry_node → trace[0]` 視為已走過 |
| §4.5 目前位置 | 正確 | 非終態：`exited_at === null` 那一筆；終態：沒有「目前位置」，由 h1 狀態徽章、圖的 aria-label、結束時間與原因表示（UI 規格表第 28 行） |
| §4.5 visit | 正確 | `TracePage` `visitOf` = 1 加上前面同一個 `node_id` 的筆數；只經過一次的節點不標 |
| 定義取得失敗時退回逐步紀錄 | 正確 | 定義是用 Enrollment 綁定的 `journey_version` 取的（不是最新版）；失敗、定義是空的、軌跡裡有定義沒有的節點，都會顯示「無法顯示旅程圖」，逐步紀錄照常顯示；Enrollment 失敗時不去取定義 |
| 遮罩只看後端標記 | 符合 | 客戶頁看 `Identifier.masked` 或 `masked_paths` 的 `identifiers[k].value`；屬性交給 `KeyValues` 依 `masked_paths`；標題用 `attributes.name` 的條件是它不在 `masked_paths`（profiles.md §1）；搜尋結果只看 `masked`；軌跡頁沿用 `masked_paths`。沒有任何地方比對 `***` |
| 未知 IdentifierType | 符合 DESIGN-04 §5 | `Identifier.type`、`matched_by` 宣告成 `string`；`identifierLabel` 遇到不認得的值時顯示原值 |
| `res.clone().json()` | 可以接受 | 只改成功回應這一行。正式環境每次 fetch 都是新的 Response，clone 只是多緩衝一份 body，對行為沒有影響；原因是測試的 deferred 共用同一個 Response，加上 StrictMode 會讀兩次，而 qa 的測試已經鎖住不能改。錯誤路徑 `toError` 沒有改 |
| 新相依 | 只有兩個，版本固定 | `web/package.json`：`@xyflow/react` 12.12.0、`@dagrejs/dagre` 3.1.1。lockfile 其他新增都是這兩個的間接相依（graphlib、xyflow/system、d3-*、classcat）；`@types/react*` 少了 `dev: true` 是因為它們成了 xyflow 的 peer，屬正常 |
| 路由 | 依 routes.md | `/` 保留；`/journeys`、`/journeys/:id?version`、`/enrollments`（不收 q）、`/customers?q`、`/customers/:id`、`/enrollments/:id` 換成 `TracePage`；`currentNav` 的對應依 §3；403 頁與麵包屑導到 `/journeys`；`/` 和 `/enrollments` 互相有頁內連結；客戶頁單筆直進的資訊用 `history.state` 帶，不放在網址上 |
| D-048／D-050／D-062 | 符合 | 搜尋同步 `?q=`；只有一筆時 `replace` 直接進客戶頁；多筆時 caption 註明最多 20 筆；查無結果時列出可能原因。列表沒有人數欄。旅程頁有版本下拉選單，切到舊版顯示「不是最新版本」，404 顯示「找不到這個旅程」，不疊軌跡 |
| 負責檔案與禁區 | 沒有越界 | 只改 `web/src/`、`web/package.json`、`package-lock.json`；`tokens.css`、`api/`、`docs/`、`web/tests/acceptance/` 都沒動（acceptance 目錄只有 qa 的兩個 test commit） |
| 寫死的設定值 | 沒有 | 沒有 port、網址、金鑰；樣式都用 tokens 變數 |
| 安全 | 沒有問題 | React 的文字輸出不用 `dangerouslySetInnerHTML`；網址參數一律經過 `encodeURIComponent`；複製鈕只用在沒有遮罩的 ID |

## 不擋合併的備註（不用在這張單改）

1. `graph/model.ts:6-7` 的 `NODE_W`／`NODE_H` 是把 tokens.css 的 `--node-width`／`--node-height` 抄成常數，designer 改 token 時兩邊可能對不上；uat 看圖時順便核對。
2. `EnrollmentBrowsePage.tsx:25` 的旅程下拉選單只取第一頁 `limit: 100`，超過 100 個旅程就選不到後面的（網址上指定的旅程仍會保留成選項）；v0.2.0 單一租戶可以接受，以後有需要再改成搜尋式選單。
3. `CustomerSearchPage.tsx:311`：伺服器回 `400` 時一律顯示「請輸入要搜尋的內容」，超過 200 字也會顯示這句；前端已經擋掉空字串，所以實際上只有超長輸入會遇到。
4. UI 規格第 42 行說迴圈要在圖上標「步驟 6、8」，目前沒做；v0.2.0 的定義不會有迴圈（D-055），等到 v0.3.0 畫布那張單再補。
5. worker open_questions 裡的 React Flow 歸屬連結、軌跡頁沒有「複製 Enrollment ID」，屬於設計／甲方的取捨，已經列進 qa 證據的 uat 項目 4、5，不是程式碼問題。
