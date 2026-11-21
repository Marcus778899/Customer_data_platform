# 設計：刪除從未發佈的旅程（journey-delete-design）
<!-- #276 第 6 點（甲方 2026-11-19）。J = api/src/main/java/com/cdp、A = api/src/test/java/com/cdp/acceptance、W = web/src、WA = web/tests/acceptance -->
- 契約：`journeys.md` §3 表格與存檔時機、§3.6、§4.1、§6；`auth.md` §2；`journey-engine.md` §5（`JOURNEY_PUBLISHED`）；schema 正本 spec 07 §12「V11」；規則 `docs/domain/rules.md`「刪除旅程」
- 現況：`JourneyDraftService.delete` 對 `DRAFT` 丟 `DRAFT_REQUIRED`；未發佈的旅程只有 `journey` 一列＋可能的 `REJECTED`／`WITHDRAWN` 審核（核准即轉 `ACTIVE`，§3.1 同步亦同交易轉 `ACTIVE`），沒有 `journey_definition`、Enrollment、outbox、軌跡。所有寫入都先 `JourneyStore.lock`（`JdbcJourneyStore` `FOR UPDATE`）
## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 新端點 `DELETE /api/v1/journeys/{journey_id}`；`DELETE …/draft` 對 `DRAFT` 維持 `409` `DRAFT_REQUIRED` | 「刪旅程」與「捨棄草稿」語意不同；既有驗收（`A/journey-lifecycle-service/DraftApiAcceptanceTest`）不用改 |
| 2 | 軟刪除：V11 加 `journey.deleted_at`／`deleted_by`＋`ck_journey_deleted`（只允許 `DRAFT`），草稿欄位與 `journey_review` 不動 | 守住審核 #7「審核紀錄不可刪」；保留刪除者（同 `auth.md` §4 使用者不刪除的理由）；誤刪可由維運還原，不屬不可逆 |
| 3 | 隱藏：`JdbcJourneyStore.find`／`lock` 與 `JdbcJourneyQueryStore` 的 `BASE`（列表、明細）加 `deleted_at IS NULL`。其餘路徑自動 404：`JourneyReviewService` 各方法先 `lock`／`find`；`JourneyService.lockExisting`（§3.1）因沒有 `journey_definition` 回 `JOURNEY_NOT_FOUND`；版本、節點統計、引擎只讀 `journey_definition` | 改兩個 Store 就涵蓋全部入口，不必逐一端點加判斷 |
| 4 | `JourneyDraftService.deleteJourney(String journeyId, Caller caller)`（`@Transactional`）：`lock` → 空 = `JOURNEY_NOT_FOUND`；`status != DRAFT` = `JOURNEY_PUBLISHED`；`reviews.findPending` 有值 = `DRAFT_IN_REVIEW`；`journeys.markDeleted(journeyId, caller.userId(), clock.instant())`；成功後 `log.info` `journey_deleted journey_id= user_id=` | 檢查順序同契約 §3.6；與草稿存檔、送審、核准、§3.1、停止共用同一列鎖，併發結果見契約 §3.6 |
| 5 | `JourneyStore.markDeleted(String journeyId, String userId, Instant now)`：SQL 見 spec 07「V11」寫入列，影響 0 列丟 `IllegalStateException`（已持鎖不應發生） | 條件式更新＋CHECK 兩層擋住「刪到已發佈的旅程」 |
| 6 | `ErrorCode.JOURNEY_PUBLISHED(HttpStatus.CONFLICT)`；`JourneyDraftController` 加 `@DeleteMapping("/{journeyId}")` 回 `204`；`SecurityConfig` 加 `requestMatchers(HttpMethod.DELETE, V1 + "/journeys/*").access(editorSession)` | 角色同草稿寫入（session 限定，Bearer 403） |
| 7 | 不留 `Review`、不加稽核表；刪除者與時間存在 `journey` 列＋結構化 log | `ReviewAction` 只有 `PUBLISH`；刪除不審核（同停止，D-060 精神），沒有既有稽核表可接 |
| 8 | （designer 需調查 ①）條件試算的共用測試向量寫進 `journeys.md` §4.1（25 條）；已逐條對照現行 `ConditionEvaluator.leaf`／`jsonEquals`／`numeric`，後端不需改程式；前端 condition-compare-ux 的試算以它為驗收依據 | 一份表兩端對照，避免前端試算與引擎判斷分歧 |
| 9 | （需調查 ③）手動儲存沿用 `PUT …/draft` 的 `revision`＋`409` `DRAFT_CONFLICT`，不加 API；`journeys.md` §3 補一句「存檔時機由前端決定」與刪除後的 `404`。（需調查 ②）角色、錯誤碼、審核紀錄保留見契約 §3.6、`auth.md` §2 | 存檔時機不影響後端並發控制 |
## 業務規則與術語：`rules.md` 新增「刪除旅程」3 條；`README.md` 核心規則加 1 行、檔案清單補章名；`glossary.md` 加「刪除旅程」
## 設定（寫進 .env.example，這裡不寫值）：無
## 威脅與對策
| 資產 | 威脅 | 對策 |
|---|---|---|
| 未發佈旅程的草稿內容 | 其他編輯者惡意或誤刪；CSRF 誘導刪除；以金鑰批次刪除 | 只收 session（`auth.md` §2）＋CSRF（§1.1）；留 `deleted_by`／時間與 log；軟刪除可由維運還原 |
| 已上線旅程、版本、旅程進度 | 刪到已發佈的旅程；與核准或 §3.1 同時進行而刪掉剛上線的旅程 | 列鎖後才判斷 `status`；條件式 `UPDATE … status = 'DRAFT'`＋`ck_journey_deleted` |
| 審核紀錄 | 刪除連帶抹掉退回紀錄 | `journey_review` 不動（做法 #2） |
| 已刪除旅程的內容 | 經明細、草稿、`/reviews`、列表讀回 | 做法 #3；驗收逐一端點驗 404 與列表不出現 |
## 受影響的既有驗收測試
已 grep `A/`、`WA/` 的 `DRAFT_REQUIRED`、`"DELETE"`、`/journeys/` 與「尚未發佈」。後端：`DraftApiAcceptanceTest` 的 `DRAFT_REQUIRED` 案例不變；`A/user-auth-api/RoleMatrixAcceptanceTest`、`A/security/DefaultDenyAcceptanceTest` 的「未列出路徑」不含 `DELETE /journeys/{id}`，不矛盾。

| 測試檔 | 跟新契約哪裡矛盾 | 交給哪張單改寫 |
|---|---|---|
| `WA/journey-review-ui/matrix.test.tsx`「從未發佈的草稿（status DRAFT）」 | 斷言捨棄草稿、停止停用並說明「尚未發佈的旅程不能停止或刪除草稿」；新規格會加刪除入口、改停用說明（canvas-interaction-ui 第 4、6 點） | journey-header-save-delete（canvas-interaction-ui 規格的前端單） |
## 檔案分工
| 檔案 | 負責 |
|---|---|
| `api/src/main/resources/db/migration/V11__journey_soft_delete.sql`（新）、`J/journey/JourneyDraftService`、`JourneyDraftController`、`JourneyStore`、`J/persistence/JdbcJourneyStore`、`JdbcJourneyQueryStore`、`J/common/web/ErrorCode`、`J/security/SecurityConfig` 及其單元測試 | journey-delete-api（backend） |
| `W/` 刪除入口、確認框、刪除後導回列表、`W/api/`（`deleteJourney`）、`W/pages/review/model.ts` 停用說明 | journey-header-save-delete（frontend；其餘檔案依 canvas-interaction-ui 規格） |
## 派工順序
| task_id | 角色 | 內容 | 驗收目錄 | 相依／平行 |
|---|---|---|---|---|
| journey-delete-api | backend | 做法 #1～#7；驗收：三種角色＋金鑰權限、404／`JOURNEY_PUBLISHED`（`ACTIVE`、`ABORTED`）／`DRAFT_IN_REVIEW`、退回或撤回後可刪、刪後旅程／草稿／`/reviews`／`PUT`／`submit`／§3.1 帶此 id 皆 404 且列表（含 `?review_status=REJECTED`）不出現、再刪 404、審核紀錄列仍在 DB、`DELETE …/draft` 對 `DRAFT` 仍 `DRAFT_REQUIRED` | `A/journey-delete-api/` | 無；與前端單平行（前端對契約 mock） |

- 不另開整合單：只有一支無副作用連鎖的端點，後端驗收以真 API 驗；前端單合併後由 uat 實測刪除流程
## 自行決定（可逆、在範圍內；甲方核可設計時一起看，要改再說）
| 項目 | 決定 |
|---|---|
| 端點與錯誤碼 | 獨立 `DELETE /journeys/{id}`；新碼 `JOURNEY_PUBLISHED`；已刪除視同不存在（再刪 404，不做冪等 204） |
| 誰能刪 | `EDITOR` 以上、不限建立者（同草稿編輯不限建立者）；不經審核 |
| 送審中 | 擋下（`DRAFT_IN_REVIEW`），先撤回；不在刪除時自動撤回 |
| 併發編輯 | 刪除不帶 `revision`；別人存檔後被刪，他下次存檔得 `404`，前端提示旅程已被刪除 |
| 保留方式 | 軟刪除、保留草稿與審核紀錄，不提供復原 API（做法 #2） |
## 待甲方決定／需要調查
- 待決定：無。刪除本身是甲方 #276 的要求；本設計不實際刪除任何資料（軟刪除），不屬 ④。若甲方要求連資料一起實體清除（含審核紀錄），屬 ④ 且與審核 #7 衝突，需另開 D-編號
- 需調查：無（designer 轉來的三項已收斂，見做法 #8、#9）
## 風險
| 風險 | 對策 |
|---|---|
| 之後新增的 `journey` 讀取漏加 `deleted_at IS NULL`，已刪除的旅程又出現 | 過濾集中在 `JdbcJourneyStore.find`／`lock` 與 `JdbcJourneyQueryStore.BASE`；spec 07「V11」讀取列寫明規則 |
| 退版到 V11 前的程式時，已刪除的旅程重新出現 | 資料不失、可再刪；發版說明註記 |
