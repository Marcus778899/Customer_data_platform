PASS f8e18e5

# ci-container-share 驗證（PR #124）

## 1. 改動範圍（逐檔看 diff）
`git diff develop...HEAD --stat`：5 個檔案（SharedContainers.java 新增，Delivery／Engine／RelayLoop／Security 四個 TestSupport），162+ 24-。
沒有任何測試類別、斷言、測試方法、測試資料被改；只有容器來源換成 `DynamicPropertyRegistrar`。api/src/main、pom、.github、Makefile、compose.yaml 都沒動。

## 2. 本機完整跑（原始結果）
`docker compose --env-file .env -f .worktrees/ci-container-share/compose.yaml run --rm test`
```
[INFO] Tests run: 391, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:42 min
```
391 與 develop 上（run 41122642212 的 Check 日誌）相同。容器建立紀錄（輸出中 "Creating container for image"）：rabbitmq 4 次、postgres 17 次（改動前約 33／39）。
postgres 17 次的組成：1 共用 + 2 個 pause 類別各 1 組 + 沒走 SharedContainers 的測試（SchemaV5Upgrade、SchemaV6AppUser、SchemaV7ProfileStore、StartupValidation 等直接起自己的 PostgreSQL，本張範圍外）。

## 3. 隔離評估
- 每個 context 的 registrar 都新建 `db_<uuid>` 與 `vh_<uuid>`，PostgreSQL 與 RabbitMQ 的 database／vhost 都不重複；queue／exchange 在各自 vhost 內，固定名稱不會跨 context 碰撞。跨 context 的輪詢迴圈（relay 等）各自只連自己的 database／vhost。
- PostgreSQL `max_connections=1000` 已因 context 快取多（每個 Hikari 預設 10 條）處理。
- 共用容器 JVM 內 lazy singleton、不交給 Spring 管，Ryuk 回收。
- 本機一次與 CI 三次全綠，沒有偶發失敗。

## 4. Pause 類別判斷（以屬性判斷）
- 實際掃描：`cdp.delivery.requeue-delay=PT2S` 只在 DeliveryResilienceAcceptanceTest（會 docker pause）；`cdp.relay.confirm-timeout=PT8S` 只在 RelayStuckTimersAcceptanceTest（會 docker pause）。application-test.yml 預設是 PT0.2S／PT5S，RelayRewriteAcceptanceTest 用 PT2S（共用容器，正確）。沒有其他類別剛好設到同值；會 pause 的類別只有這兩個（grep pause 只有這兩個加 SharedContainers 的註解）。
- 脆弱性（minor，不影響 PASS）：判斷靠魔術值。若有人把 Resilience 的 requeue-delay 改成別的值，會悄悄改走共用容器，docker pause 會凍結所有共用測試；反向（別的類別用同值）只會多起專屬容器，無害。建議以後改成明確標記（自訂 test property 或註解），或在 pause 測試加護欄。可列 backlog。

## 5. CI 連續 3 次 Check（run 41124075551，同一 commit f8e18e5，一次跑完再 rerun）
| attempt | Check 結論 | Check 時間 |
|---|---|---|
| 1 | success | 20:32:59 -> 20:36:25 = 3m26s |
| 2 | success | 20:36:52 -> 20:39:59 = 3m07s |
| 3 | success | 20:41:54 -> 20:45:34 = 3m40s |

三次 Check 日誌都是 Tests run: 391（attempt 3 已確認），Security、Changes 亦全綠。

比較（Check job 時間）：
- develop push（ci-surefire-fork 合併後，run 41122642212）：20:22:32 -> 20:27:03 = 4m31s
- 同類型 pull_request 的 ci-surefire-fork（run 41121246780）：5m29s；gitleaks-test-allowlist PR（41123595670）：5m32s
- 本張三次平均 3m24s，比 develop push 快約 1m07s（約 25%），比 PR 型 run 快約 2m（約 38%）。

注意：develop 上最近 push 的 CI 整體是 failure，原因是 Security job（gitleaks，另一張 gitleaks-test-allowlist 處理），不是 Check；本 PR 的 Security 全綠。

## 判定
驗收條件四條皆已實際驗證，沒有 FAIL 項目。Minor 1 項：pause 類別以魔術值判斷（見第 4 節）。
