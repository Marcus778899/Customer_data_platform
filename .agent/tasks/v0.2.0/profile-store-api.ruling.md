TEST_WRONG 5d4a345

驗收測試 lock 檢查：跟 lock（commit b210339）時一樣，沒被改過

觸發來源：worker disputes（不是 qa FAIL，沒有 qa 證據檔要逐條判）。lock 檢查的是本單目錄 `acceptance/profile-store-api/`，沒被改過；爭議的檔案是目錄外的既有驗收，依現行契約判定。

## 逐條判定

| 測試 | 契約條文 | 判定與理由 |
|---|---|---|
| `acceptance/query/EnrollmentQueryAcceptanceTest.java::list_invalidParameters_return400ValidationFailed`，清單第 1 個 URL `/api/v1/enrollments`（第 505 行） | journey-engine.md §4 表格第 1 列：`q`「v0.1.0 必填；v0.2.0 起選填，省略 = 瀏覽最近的 Enrollment〔D-030〕」；DESIGN-03 做法 #6、範圍 A「最近 Enrollment 瀏覽（D-030）」 | **測試錯**。這個測試是 BE-03（v0.1.0，commit 6c0c8f5）寫的，當時 q 必填；v0.2.0 契約改成選填，不帶 q 應回 `200` `Page<EnrollmentSummary>`。本單 lock 的 `EnrollmentBrowseAcceptanceTest`（第 58 行 `getOk("/api/v1/enrollments?limit=100")`）也照契約要求 200，兩個測試互斥 |
| 同一個測試，其他 7 個 URL（`q=`、`limit=0`／`101`／`abc`、`status=BOGUS`／`WAITING,BOGUS`、不合法 cursor）與 cursor 的 `errors[].field` 斷言 | §4 `limit` 1–100、`status` 為 `EnrollmentStatus` 列舉、`cursor` 不透明字串；錯誤 `400` | 符合契約，保留。`q=`（有帶但是空字串）回 400：契約沒寫，見下方「契約問題」1；實作目前也回 400，不影響本次判定 |
| `acceptance/profile-store-api/EnrollmentBrowseAcceptanceTest`（全部） | §4 同上 | 符合契約，不用改 |

## qa 要怎麼改

- `api/src/test/java/com/cdp/acceptance/query/EnrollmentQueryAcceptanceTest.java` 第 505 行：從 `list_invalidParameters_return400ValidationFailed` 的清單刪掉 `"/api/v1/enrollments"` 這一項；其他項目與第 515–516 行不變
- 不用在這個檔案補「不帶 q 回 200」的測試，`EnrollmentBrowseAcceptanceTest` 已涵蓋
- 範圍：這個檔案不在本單 acceptance 子目錄，但本單驗收條件「既有驗收全綠」要靠它，且是本單把 q 改成選填才觸發。建議 manager 把這一個檔案（只限上述一行）列進本單的驗收測試範圍，由 qa 在本單的分支修改、修改後重新 lock；不另開單（另開單時本單 CI 會一直紅）

## 附註意見（不影響結論）

| 項目 | 意見 |
|---|---|
| `PUT /profiles` 只在內容變動才推進 `updated_at` | 可以。profiles.md §2 沒規定；`updated_at` 用在搜尋排序（§3「再 `updated_at` 新到舊」），表示「資料最後一次變動」比「最後一次被呼叫」合理，且重送相同內容（ETL 重跑）不會擾動排序。屬技術細節，不屬決策分級四類；建議下次改契約時在 §2 補一句「內容沒變不更新 `updated_at`」 |
| EMAIL CHECK 用 `^[^@\s]+@[^@\s]+$` | 可以。DB 約束與 API 驗證（profiles.md §1.1 第 32 行）一致，API 擋得下的 DB 才不會漏；spec 07 §12 第 448 行「含 `@`」是較寬的描述，不矛盾（符合正則的值一定含 `@`）。建議下次改 spec 07 時把該格改成引用 profiles.md §1.1 |

## 契約問題

1. journey-engine.md §4 沒寫「帶了 `q` 但為空字串或只有空白」要 400 還是當成省略；現行測試與實作都是 400。屬技術細節（非四類），建議 architect 下次改契約時補明「`q` 有帶時須 1–200 字，否則 400」，與 profiles.md §3 一致
