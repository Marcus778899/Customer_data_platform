APPROVE 4fb7028

## 檢查範圍
- PR #185（`fix/trace-customer-name` → develop），head 4fb7028；CI：Changes／Check／Security 全 pass
- 對照：派工單 `.agent/tasks/trace-customer-name.md`、契約 `docs/contracts/profiles.md`、`docs/contracts/auth.md` §2、UI 規格 `docs/ui/enrollment-trace.md`（畫面 1、麵包屑）、D-014、D-021、D-080、D-082

## 負責檔案
- worker commit c517fbe 只改 `web/src/pages/TracePage.tsx`、`web/src/pages/trace/model.ts`，都在負責檔案內
- `web/tests/acceptance/**` 的變更來自 qa commit（7bd01a3、6f5ed6b、8242e16 仲裁 TEST_WRONG 後修正），worker 沒改驗收測試；4fb7028 是空 commit 交件

## 驗收條件對照
| 條件 | 結果 |
|---|---|
| 取得順序 1 軌跡快照姓名 | `customerLabel` 先用 `customerName(d)`，有姓名就不取客戶資料（`needProfile` 為 false 時 `useFetch(null)` 閒置） |
| 2 客戶資料姓名（未遮罩） | `attributes.name` 是非空字串且 `masked_paths` 不含 `attributes.name` 才用 |
| 3 第一個可顯示識別碼（後端遮罩值） | 取 `identifiers` 第一個非空 `value`，原樣顯示，`masked` 帶出「（部分資料已遮罩）」 |
| 4 未具名客戶 | 其餘情況、客戶資料載入中或失敗都回「未具名客戶」 |
| 麵包屑中段與副標同一標示、中段連客戶頁 | 兩處都用 `customer.text`；連結 `/customers/{encodeURIComponent(profile_id)}` 未變 |
| 主要畫面不出現客戶 ID | 標示來源只有姓名與識別碼值，不用 `profile_id` |
| 客戶資料失敗頁面照常 | `profile.error` 不被讀取，不會出錯誤框；只用 GET |

## bug 與安全
- 403：`getProfile` 預設 `pageOn403` 為 true，403 FORBIDDEN 會切整頁無權限；但 `auth.md` §2 中 `GET /profiles*` 與 `GET /enrollments*` 的角色相同，看得到軌跡就看得到客戶資料，實務上不會發生，不列為問題
- hooks 都在 `DetailBody` 頂層、沒有條件式呼叫；重新整理時 `profile_id` 不變，`loadProfile` 不重建、不重打
- 不新增寫死設定、不改 API 與契約；遮罩值照後端原樣顯示，沒有還原或補全

## 測試
- 驗收測試 `web/tests/acceptance/trace-customer-name/` 涵蓋四個順序、遮罩姓名、404／500／連線失敗、延遲載入、軌跡 404 不取客戶資料、隱私；既有 `trace-graph.test.tsx`、`advanced-states.test.tsx` 已依新行為改寫；CI Check（含 `make check`）通過
- 觀察（不擋）：`web/tests/unit/trace/model.test.ts` 沒有替新函式 `customerLabel` 加單元測試，分支目前只靠驗收測試覆蓋；CI 覆蓋率檢查已過
