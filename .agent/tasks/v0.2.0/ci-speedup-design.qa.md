PASS 9bf84b7

## 範圍
- `git diff --stat develop...HEAD`：只有 `docs/design/ci-speedup.md`（+56），符合負責檔案；`.github/`、Makefile、api、web、compose.yaml 未動。
- docs 單，無程式與 lint 變更，未跑 make check（回報也說明未跑完，因僅改文件，可接受）。

## 抽查 CI 時間數字（gh run view <id> --json jobs，自行查詢）
- 41091268596 Check：16:35:26 -> 16:42:15 = 409 s（文件 409，相符）
- 41088821210 Check：16:20:35 -> 16:27:18 = 403 s（文件 403，相符）
- 41054164223 第 2 次 Check：12:24:16 -> 12:30:10 = 354 s（文件 354，相符）；run 本身 attempt=2，createdAt 12:16:58、updatedAt 12:30:10，印證「13 分鐘 = 兩次 attempt 相加」的說明
- 13 分鐘那次不是排隊或快取問題：與文件相符
- gh run list -L 200 回傳 200 筆，develop push 132 筆，與文件 95（docs-only）＋35（合併）＋其他 4 的量級一致
- 抽查 `application-test.yml` 有 `server.port: 0`、api/pom.xml 目前沒有 forkCount（與文件「現況單一 fork」一致）

## 驗收條件
1. 至少 3 次 run 拆解，附數字與指令：有（5 個 job log）
2. 13 分鐘解釋：有且已驗證
3. 加速方案（節省、檔案、風險、受保護路徑）：有 5 項＋不採用清單
4. 實作順序與拆單、待決定／自行決定分表：有（#1 列為待決定④）

## 非阻擋備註（minor，不影響 PASS）
- 方案 #1 寫「grep docs/、spec/ 無結果」不完全正確：api/src/main 的註解與 SQL 註解含 `docs/spec/...` 字串，但都只是註解，執行期與測試不讀取（測試目錄無讀取 docs/.agent 路徑的程式），結論仍成立。
- 路徑 `relay-loop-rewrite/RelayLoopTestSupport` 與實際目錄名一致（目錄確實叫 relay-loop-rewrite，含連字號，不是合法 Java 套件名，屬既有問題）。
- run 41091268596 的 Security job 實際為 failure（Scan 步驟，對應 D-074 依賴漏洞），文件只寫「Security 約 45 秒」，沒有誤述結果。
