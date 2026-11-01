APPROVE f8e18e5

# Review ci-container-share（PR #124）

## 檢查項目
| 項目 | 結果 |
|---|---|
| 改動範圍 | `gh pr diff 124`：5 個檔案，正好是派工單的負責檔案（`support/SharedContainers.java` 新增，4 個 `*TestSupport.java`）。`api/src/main/`、`api/pom.xml`、`.github/`、`Makefile`、`compose.yaml` 都沒動；沒有改到任何測試類別、斷言、測試方法或測試資料；沒有排除或跳過測試 |
| 照設計做 | 符合 `docs/design/ci-speedup.md` 做法 #3：`PgHolder`／`RabbitHolder` 用 holder-class 做 lazy singleton，不是 Spring bean，由 Ryuk 回收；每個 context 透過 `DynamicPropertyRegistrar` 建 `db_<uuid>`＋`vh_<uuid>`（並給 admin 這個 vhost 的權限）；兩個會 pause 的類別保留 `@ServiceConnection` 專屬容器 |
| 隔離 | 每次建立 context 都會呼叫一次 registrar，拿到新的 database 與 vhost；queue／exchange 名稱固定沒關係，因為都在各自的 vhost 裡。測試開了 `@ServiceConnection` 的專屬容器時，registrar 是 no-op，不會跟 ConnectionDetails 搶。`max_connections=1000` 夠用：每個 JVM 最多快取 32 個 context，每個 context 10 條連線，約 320 條 |
| Bean 名稱衝突 | 4 個 support 類別的 registrar bean 都叫 `sharedContainers`；如果一個 context 同時 import 兩個 support，會因為 bean overriding 而啟動失敗（會明確報錯，不會悄悄出錯）。grep `@Import` 確認目前沒有任何類別同時 import 兩個 support |
| 寫死設定、安全 | 只有測試程式碼。SQL 裡串接的 database 名稱是程式自己產生的 UUID，不是外部輸入；帳密取自 Testcontainers 容器；沒有 `.env` 的值，也沒有寫死 port。`HttpClient` 沒設 timeout，但只連本機測試容器，不擋 |
| 驗收條件 | ① 共用 singleton、各自的 db／vhost、pause 類別用專屬容器：從程式碼確認符合 ② 391 個測試，跟改動前相同（qa 本機與 CI 日誌都是這個數）③ run 41124075551 attempt 1–3 在同一個 commit f8e18e5 上全綠（`gh pr checks 124`：Changes／Check／Security 都 pass，headSha 一致）④ 沒有偶發失敗 |
| qa 證據的小誤差（不影響判定） | qa 寫「postgres 17 次 = 1 個共用 + …」。但 compose 的 `test` 吃 `pom.xml` 的 `forkCount=2`，每個 fork 各有一組 singleton，所以實際是 2 個共用。rabbit 4 次 = 2 個共用 + 2 個 pause 類別，剛好對得上 |

問題數：0

## qa minor 的判斷：pause 類別用屬性值選容器（不擋本單）
- **qa 說的失敗情境不會發生**：兩個 pause 類別都用 `@Autowired RabbitMQContainer rabbitContainer`（必填注入，`DeliveryResilienceAcceptanceTest.java:110`、`RelayStuckTimersAcceptanceTest.java:35`），而且 pause 的對象是 `rabbitContainer.getContainerId()`。如果有人把 `PT2S`／`PT8S` 改成別的值，`@ConditionalOnProperty` 就不會建立容器 bean，context 在啟動時就會因為 `NoSuchBeanDefinitionException` 失敗，測試直接紅，走不到 `docker pause`。共用容器的 holder 是 private 的，測試也拿不到共用容器的 id。所以會是「明確報錯」，不會「悄悄凍結共用容器」。
- 反過來，如果別的類別剛好設成同一個值，只會多起一組專屬容器，比較慢但沒有壞處（qa 已經確認目前沒有這種情況）。
- **剩下的問題是可讀性**：錯誤訊息只會說找不到 `RabbitMQContainer` bean，看不出跟 requeue-delay 有關；以後新增 pause 類別的人也得知道要設這個魔術值。這是維護成本，不是正確性問題，**不擋本單**。
- 建議開後續單（backlog、低優先，負責：qa）：
  - 範圍：兩個 support 類別的條件改成明確標記，例如測試專用屬性 `cdp.test.dedicated-containers=true`（`@ConditionalOnProperty` 加上 registrar 的判斷）；兩個 pause 類別的 `@SpringBootTest(properties)` 加上這個屬性；`SharedContainers` 的 javadoc 寫清楚「要 pause 容器就加這個屬性」
  - 負責檔案：`DeliveryTestSupport.java`、`RelayLoopTestSupport.java`、`DeliveryResilienceAcceptanceTest.java`、`RelayStuckTimersAcceptanceTest.java`（後兩個是 qa lock 的驗收測試，只加一行屬性，不改斷言，所以要由 qa 的單來做）
  - 驗證：全部測試數量不變、容器啟動次數不變

## 後續單建議（不在本單改）
| 項目 | 範圍 | 建議負責 |
|---|---|---|
| forkCount 重新比較 1／2／3 | 要。ci-surefire-fork 那次的 review 發現，fork=2 讓單一類別變慢 1.66 倍，主因是每個 context 都起 RabbitMQ／PostgreSQL 在搶 CPU；本單之後，每個 JVM 只會起 1 組共用容器（加上 pause 類別各 1 組），前提已經變了。量法：在**同一個 commit** 上只改 `-DforkCount`（1、2、3 各至少跑 2 次，因為 runner 差異有 ±15%），記錄 Check 時間、surefire 時間、各類別耗時加總、容器啟動次數；fork=3 時共用容器會變成 3 組，也要看 4 vCPU 撐不撐得住。最後只改 `api/pom.xml` 的 `forkCount`（受保護路徑，要 architect review） | backend（改 `api/pom.xml`；臨時試不同 fork 數用 CI 的 `-D` 或分支，不 commit）；architect review |
| `docs/design/ci-speedup.md` 記下實測值 | 做法 #2 實測只省約 0.5 分鐘（設計預估 2.5）；做法 #3 本單：Check 3m26s／3m07s／3m40s（平均 3m24s），develop push 4m31s，同類 PR 約 5m30s；容器啟動 RabbitMQ 33→4、PostgreSQL 39→17（剩下的是 Schema*／StartupValidation 這類自己起容器的類別，本單沒碰）；ci-surefire-fork review 的變慢倍數 1.66；量法「同一個 commit 只改一個參數」。forkCount 比較完一起寫，只改一次文件 | architect（只改文件的 chore；可以跟 forkCount 那張併在同一輪：backend 先量，architect 再寫） |
| （可選）剩下的 PostgreSQL 起動 | `SchemaV5Upgrade`、`SchemaV6AppUser`、`SchemaV7ProfileStore` 各起自己的 PostgreSQL，`StartupValidation` 類每個測試都起整個 app，這些都可以改用共用容器的獨立 database。等 forkCount 的數字出來再評估值不值得做 | 列進 backlog，不急 |

## 驗證（本次執行）
- `gh pr view 124 --json headRefOid,files` → head f8e18e5，5 個檔案，都在負責檔案內
- `gh pr diff 124`：逐行讀過
- `gh pr checks 124` → Changes pass 5s、Check pass 3m40s、Security pass 50s
- `gh run view 41124075551 --json attempt,headSha,conclusion` → attempt 3，success，headSha f8e18e5
- worktree 裡 grep：`pause`、`requeue-delay`、`confirm-timeout`、`RabbitMQContainer` → 只有兩個 pause 類別會注入容器並 pause；`application-test.yml` 的預設值是 PT5S／PT0.2S；`RelayRewriteAcceptanceTest` 用 PT2S confirm-timeout，走共用容器
- 依指示沒有跑需要容器的測試
