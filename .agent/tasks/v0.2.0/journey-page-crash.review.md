APPROVE fc20857

## 檢查範圍
- `gh pr diff 163`（headRefOid fc20857）對照派工單 `.agent/tasks/journey-page-crash.md`、契約 `docs/contracts/journeys.md`、UI 規格 `docs/ui/journey-platform.md`（狀態表、無障礙）、D-062
- CI：Changes、Check（含覆蓋率閘門）、Security 全部 pass
- 負責檔案：改了 `web/src/App.tsx`、`web/src/graph/model.ts`、新增 `web/src/graph/model.test.ts`，都在負責範圍內；`web/tests/acceptance/journey-page-crash/` 是 qa 的 commit 1201a81；沒有改到後端、契約或驗收測試以外的檔案；沒有寫死的設定值

## 重點判定
| 項目 | 判定 |
|---|---|
| `outsOf()` 防呆（model.ts:70-72） | 合理。`outputs` 是 null、缺漏或不是物件時都當 `{}`；4 個使用處（`deriveOverlay` 2 處、`buildModel`、`textOrder`）全部換掉，順便把原本 `byId.get(...)!` 的非空斷言改成安全取值。`outputs` 的目標是 null 時：`buildModel` 的 `ids.has(null)` 為 false 會跳過，`textOrder` 的 `byId.get(null)` 會 continue，不會丟例外。grep `web/src` 沒有其他直接讀 `.outputs` 的地方。`TracePage` 的圖形軌跡共用 `deriveOverlay`／`buildModel`，客戶頁軌跡也一起受到保護 |
| `PageBoundary`（App.tsx:37-62）放在 Shell 內 | 合理。外框導覽在錯誤時仍可用，符合「不整頁白掉」；登入、auth error 等 Shell 外的畫面本來就不經過頁面元件 |
| 用 `resetKey` 重置 | 正確。`resetKey={here}`（pathname+search）跟 `Shell` 的 `menuKey` 同一個來源；換頁或切換 `?version=` 時，`componentDidUpdate` 清掉 failed，子樹重新掛載。限制：在同一個網址上沒辦法重置，只能按「重新載入」（`window.location.reload`），可以接受 |
| 錯誤狀態是否符合 docs/ui/journey-platform.md | 符合。規格狀態表的旅程頁錯誤只定義 404 與 5xx（`JourneyPage` 原本就處理，驗收測試 `?version=9` 驗了 404）；渲染例外是規格沒寫到的新狀態，做法沿用 `ErrorBox` 的樣式（`msg error`、`role="alert"`、粗體標題＋說明＋次要按鈕），符合無障礙段「錯誤 `role="alert"`」。文案「這個頁面無法顯示。資料格式異常，請稍後再試或回到上一頁。」是中性書面語（D-017），沒有暴露堆疊或內部資訊 |
| 測試 | 單元測試 `model.test.ts` 測了 `outsOf` 的 null、缺漏、非物件、正常 4 種情況，以及 EndNode `outputs=null` 時的 `buildModel`。驗收測試 24 條涵蓋實際的資料形狀（outputs null）、契約允許的邊界形狀、違反契約的形狀（觸發錯誤邊界）、外框還在 |

## 非阻擋備註（不需要修改）
- 錯誤邊界文案是 frontend 自己定的；designer 下次更新 `docs/ui/journey-platform.md` 時可以把「頁面渲染失敗」補進狀態表
- `PageBoundary` 沒有 `componentDidCatch` 上報；React 本身會把錯誤印到 console.error，v0.2.0 沒有前端錯誤蒐集機制，維持現狀即可

## 後端 outputs=null（backlog #161）是否要在 v0.2.0 處理
判斷：維持 backlog、不擋 v0.2.0，但建議在 v0.3.0 畫布開工前處理掉。理由：
- 前端已經能容忍，引擎 `DefaultEnrollmentAdvancer.java:373` 也把 null 當成沒有出口；v0.2.0 是唯讀，不會再造成白畫面或執行錯誤
- 補充給 #161：這不只是舊資料的問題。現行的建立驗證 `JourneyService.java:182`（`validateOutputs`）把 `n.outputs() == null` 當成 `Map.of()` 處理，EndNode 等不需要出口的節點傳 null 仍然會通過、照原樣存進資料庫，所以新資料也可能出現違反契約的形狀。v0.3.0 的畫布會讀寫定義，屆時要在建立時拒絕或正規化，舊資料是否要遷移照 #161 問甲方（④）
