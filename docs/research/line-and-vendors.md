# LINE 與第三方行銷平台官方查證（研究日 2026-10-19）
**結論（事實整理，非決策；D-053 情境）**
1. 一個 channel 只能設定一組 Webhook URL，官方未見轉發／多目的地功能；多系統收事件需自建分流（建議，非官方做法）。
2. userId 依 provider 而異；同 provider 下共用。跨 provider 歸戶無官方機制；email 與電話需 LINE 申請核准。
3. 名單推送：Audience 上傳 API 只收 userId 或 IFA（IFA 限企業申請）；電話雜湊查無官方支援。代操作走 module channel，需 LINE 官方帳號管理者授權。

## 1. Webhook 與多系統收事件
- 官方原文："only one webhook URL can be set."；"the LINE Platform can't send webhook events to multiple URLs."；只送訊息的工具"may not need to receive webhook events."[1]
- 查不到官方轉發功能。第三方：單一端點驗簽後內部分流[2]；Zapier 等中介[3]。
- 官方要求："Be sure to verify the signature before processing webhook event objects."[4]
- 建議：我方僅推送時可不收 webhook；若需收，客戶既有平台可能已佔用唯一 URL，需先確認。

## 2. userId 範圍與跨 provider 歸戶
- "Even for the same user, a different user ID is issued for each provider."[5]
- 同 provider 的 LINE Login 與 Messaging API 共用 userId[5][6]；"won't change unless the user deletes their LINE account."[6]
- channel 不可移到其他 provider："you can't move the channel to another provider later."[5]
- email："you must first apply for permission to do so in the LINE Developers Console."[7]；電話："only available to corporate users who have completed certain applications."[6]
- 建議：跨 provider 歸戶需靠客戶自家登入綁定（推論，非官方）；LIFF 相關查不到。

## 3. 名單推送（Audience API 與代操作）
- 端點 POST /v2/bot/audienceGroup/upload（JSON）與 /upload/byFile；輸入為 "user IDs or IFA"，電話雜湊查無[8]
- 上限：JSON "10,000 at maximum"、檔案 "1,500,000 at maximum"；每 channel "1,000 at maximum" 受眾；保留 "180 days" 以內[8]
- 地區："Only the LINE Official Accounts created by users in Japan, Thailand and Taiwan are permitted to create audiences."[8]
- 代操作："permission to use it must be obtained from the LINE Official Account admin."；module 不可用 long-lived token；module 與 OA 的 provider 是否須一致：查不到[9]
- token：long-lived 每 channel "1"；stateless "15 minutes"、"no limit"；short-lived "30 days"、"Up to 30"[10]
- 建議：推送的 userId 須屬於同一 provider，推送前需確認（推論，非官方）。

## 4. 台灣第三方平台（以實際查到為準）
- Omnichat：公開開發者文件；Webhook 資源為 "Message"、"Interaction"、"Order"，事件 "CREATE"、"DELETE"、"UPDATE"；未見聯絡人匯入 API[11]。
- MAAC（漸強實驗室）：官網列 "他システムとの連携（Open API）"、"CSVデータのインポート・エクスポート"；未見公開開發者文件，Webhook 未提及[12]。
- Super 8：設定需 Webhook URL 指向 "https://n.no8.io/line/xxxxxxx"；未見公開 API 文件，無匯入匯出說明[13]。Vpon：查不到。
- 建議：各家 API 規格與匯入條件需直接向廠商索取。

## 5. 個資法（條文要點，非法律意見）
- 第 4 條："受公務機關或非公務機關委託蒐集、處理或利用個人資料者，於本法適用範圍內，視同委託機關。"[14]
- 第 20-1 條第 1 項："非公務機關保有個人資料檔案者，應辦理安全維護事項，防止個人資料被竊取、竄改、毀損、滅失或洩漏。"[14]
- 第 12 條第 1 項：知悉個資被竊取、竄改、毀損、滅失或洩漏時，應通知當事人[14]。
- 第 27 條 MOJ 頁顯示「刪除」（與舊搜尋摘要不一致，以 MOJ 為準）；第 12 條等修正條文"施行日期，由行政院定之"，最後生效日"未定"；各條目前施行狀態與受託者監督細節：查不到[14]。
- 建議：代管與客戶間以契約約定受託範圍，並請甲方法務確認。

來源：[1] https://developers.line.biz/en/tips/2026/07/23/using-multiple-api-tools/ [2] https://dev.to/unifyport/one-line-official-account-multiple-tools-webhook-and-token-architecture-3p5l（第三方） [3] https://docs.treasuredata.com/int/line-oa-streaming-import-integration（第三方） [4] https://developers.line.biz/en/docs/messaging-api/receiving-messages/ [5] https://developers.line.biz/en/tips/2026/06/25/provider-design-basics/ [6] https://developers.line.biz/en/faq/ [7] https://developers.line.biz/en/docs/line-login/integrate-line-login/ [8] https://developers.line.biz/en/docs/messaging-api/using-audience/ [9] https://developers.line.biz/en/docs/partner-docs/module-technical-using-messaging-api/ [10] https://developers.line.biz/en/docs/basics/channel-access-token/ [11] https://developers.omni.chat/docs/webhook-reference [12] https://cresclab.com/jp/product/maac [13] https://help.no8.io/en/articles/4161348-link-with-line-official-account [14] https://law.moj.gov.tw/LawClass/LawAll.aspx?pcode=I0050021
