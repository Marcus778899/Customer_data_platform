# 04 — 節點執行介面與引擎抽象

> 對應 README **裁決 3**（抽象分兩類）與 **裁決 4**（節點不得持有依賴）。
> 這份規格的唯一目的：**讓「自建 → Temporal」這條路，在寫下第一行程式碼時就保住。**

計劃書 7.3 列了五件會燒掉換路可能性的事。本規格用**型別**強制執行，不靠紀律。

---

## 1. 核心原則

> **節點是純函式。** 給定輸入，回傳「我想做什麼」，由引擎決定怎麼做。

節點**不能**：查資料庫、發訊息、排程自己、睡覺、拿到任何 Service 或 Repository。
節點**只能**：讀 `NodeContext`，回傳 `NodeResult`。

這使得同一份節點程式碼，在自建引擎與 Temporal Workflow 中都能原封不動執行。

---

## 2. 節點介面

```java
public interface JourneyNode {

    /** 節點型別，與 JSON 中的 type 一致 */
    String type();

    /** 宣告執行前需要哪些資料，由引擎預先查好 */
    DataRequirements requirements(NodeConfig config);

    /** 純函式：不得有任何副作用、不得存取外部資源 */
    NodeResult execute(NodeContext context);

    /** 發布前的設定驗證 */
    ValidationResult validate(NodeConfig config, JourneyDefinition journey);
}
```

### 為什麼要 `requirements()`

`ConditionNode` 要判斷「近 30 天有沒有下單」，但它不能查資料庫。
解法：**先宣告，後執行**。

```
引擎 → 讀 requirements() → 批次預查資料 → 塞進 NodeContext → 呼叫 execute()
```

好處有三：
1. 節點保持純函式，換 Temporal 時 `execute` 不用改
2. 引擎能**批次預查**（一次撈 100 個 Enrollment 需要的資料），效能遠優於節點各自查
3. Temporal 下，預查自然落在 Activity 裡，`execute` 留在 Workflow 中——分界天然吻合

```java
record DataRequirements(
    Set<String>        profileFields,     // 需要的 Profile 欄位
    Set<String>        segmentIds,        // 需要判定歸屬的受眾包
    List<BehaviorQuery> behaviorQueries,  // 需要的行為統計
    boolean            needsDeliveryHistory
) {}
```

---

## 3. `NodeContext`（純資料）

```java
record NodeContext(
    // ---- 身分與定位 ----
    String              tenantId,
    String              enrollmentId,
    String              profileId,
    String              nodeId,
    NodeConfig          config,

    // ---- 已預查好的資料（由 requirements 決定內容）----
    Map<String, Object>  profileFields,
    Map<String, Boolean> segmentMemberships,
    Map<String, Object>  behaviorResults,
    List<DeliveryRecord> deliveryHistory,

    // ---- Enrollment 自身狀態 ----
    TriggerSnapshot      trigger,          // G1
    Map<String, Object>  variables,
    Instant              enrolledAt,

    // ---- 喚醒資訊（僅 Wait 節點被喚醒時有值）----
    WakeSignal           wakeSignal,

    // ---- 時間 ----
    Instant              now               // ⚠️ 一律用這個，禁止 Instant.now()
) {}
```

**`NodeContext` 中不得出現任何介面、Service、Repository、Connection。**
建議加 ArchUnit 規則：`NodeContext` 的所有欄位型別必須是 record、enum、`java.time`、集合或基本型別。

### `WakeSignal`

```java
sealed interface WakeSignal {
    record TimerFired(Instant firedAt) implements WakeSignal {}
    record EventArrived(String eventName, Map<String,Object> properties, Instant occurredAt) implements WakeSignal {}
    record Timeout(Instant timedOutAt) implements WakeSignal {}
}
```

`WaitForEventNode` 靠 `WakeSignal` 的型別決定走 `occurred` 還是 `timeout`。

---

## 4. `NodeResult`

```java
record NodeResult(
    ResultStatus        status,
    String              outPort,        // ADVANCE 時必填
    WakeCondition       wakeCondition,  // WAIT 時必填
    Map<String, Object> variablePatch,  // 寫回旅程變數，可為 null
    List<Effect>        effects,        // 副作用，可為空
    String              decisionReason, // ⚠️ 必填，非空
    Map<String, Object> evaluated,      // ⚠️ 必填，判斷時取到的實際值
    String              terminationReason  // EXIT / FAIL 時必填
) {}

enum ResultStatus { ADVANCE, WAIT, COMPLETE, EXIT, GOAL, FAIL }   // COMPLETE：EndNode → COMPLETED
```

### `decisionReason` 與 `evaluated` 是強制的

不是選配、不是 debug 用。它們回答客服每天都會問的「為什麼這個客戶沒收到」。

```java
// ConditionNode 走 no 時
new NodeResult(
    ADVANCE, "no", null, null, List.of(),
    "會員等級為 SILVER，不在 [GOLD, PLATINUM] 中",
    Map.of("member_tier", "SILVER", "expected", List.of("GOLD","PLATINUM")),
    null
);
```

**Code review 檢查點**：任何回傳 `decisionReason` 為 null、空字串或常數字面值（如 `"ok"`）的節點，一律退回。

---

## 5. `Effect`（副作用）

> 計劃書 7.3 禁令 #2：**不可用一個資料庫交易把「推進狀態」和「發送訊息」綁在一起**——換 Temporal 後不存在跨系統交易。

節點**回傳**副作用，**不執行**副作用。

```java
sealed interface Effect {
    String idempotencyKey();   // 一律為 {enrollmentId}:{nodeId}:{visitSeq}:{effectIndex}

    record SendMessage(String idempotencyKey, String templateId, String channelId,
                       Map<String,Object> variables) implements Effect {}

    record UpdateProfile(String idempotencyKey, List<ProfileOperation> operations) implements Effect {}

    record ChangeSegmentMembership(String idempotencyKey, String segmentId, MembershipAction action) implements Effect {}

    record CallWebhook(String idempotencyKey, String url, String method,
                       Map<String,Object> payload, Duration timeout) implements Effect {}

    record NotifyInternal(String idempotencyKey, List<Recipient> recipients, String message) implements Effect {}
}
```

### 冪等鍵由引擎蓋章，節點不得自行組鍵

節點回傳 Effect 時，`idempotencyKey` 一律填 `null`。引擎在推進迴圈步驟 6 的單一交易中，依 `EnrollmentTrace.seq` 與 effects 陣列索引統一蓋上：

```
{enrollmentId}:{nodeId}:{visitSeq}:{effectIndex}
```

| 片段 | 沒有它會怎樣 |
|---|---|
| `visitSeq` | 旅程允許含等待節點的迴圈（`03` 圖驗證規則 7 只禁無等待迴圈）。少了它，第二圈走到同一個 `SendMessageNode` 時鍵值與第一圈相同，**訊息被當成重放丟掉，客戶永遠只收到第一則** |
| `effectIndex` | 一個節點回傳「發券 Webhook + 發訊息」兩個 Effect 時鍵值相同，**第二個副作用永遠不會執行** |

> 重試沿用同一把鍵——這正是重試之所以安全的原因。**任何把重試次數放進鍵值的作法都是把冪等改成「每次重試都真的再送一次」。**

**Code review 檢查點**：節點程式碼中出現字串拼接冪等鍵，一律退回。

### 執行順序保證

1. 引擎先以**單一交易**持久化：新狀態 + 軌跡 + Effect（寫入 outbox）
2. 交易提交後，`EffectExecutor` 才讀 outbox 執行
3. 執行結果回寫 outbox 狀態

因此崩潰重啟的行為是：**狀態已落地、副作用可能未執行 → 重跑 outbox，靠冪等鍵保證不重複**。

> 反過來（先發送再存狀態）會造成「訊息已發出但狀態沒存」，重啟後重發——這正是計劃書第八節「重複發送劇本」要防的事故。

---

## 6. 引擎推進迴圈

```
1. 取得可推進的 Enrollment（計時器到期 / 事件到達 / 新建立）
2. 載入 JourneyDefinition（依 enrollment.journeyVersion，不是最新版）
3. 檢查 settings.goal 與 global_exit → 命中則轉終態（GOAL_MET / EXITED），取消計時器與待送訊息，結束
     ⚠️ 兩者都是「持續評估」：每一次推進都檢查，不是走到某個節點才判定
4. 取當前節點 → requirements() → 批次預查資料 → 組 NodeContext
5. node.execute(context) → NodeResult
6. 【單一交易】
     - 更新 EnrollmentState（狀態、currentNodeId、variables、wakeCondition）
     - 寫入 EnrollmentTrace
     - 寫入 Effect outbox
     - 登記 / 取消計時器
7. 交易提交
8. EffectExecutor 非同步執行 outbox
9. status = ADVANCE 且下一節點非等待型 → 回到步驟 3（同批次連續推進）
```

### 連續推進的上限

單次推進最多連續執行 **50 個節點**。超過則寫入告警並將 Enrollment 轉 `FAILED`。
> 這是圖驗證規則 7（無等待迴圈）之外的第二道防線。驗證可能被繞過，執行期防線不能少。

---

## 7. 引擎抽象（換 Temporal 時整組替換）

```java
public interface JourneyRuntime {
    EnrollmentState start(String tenantId, String journeyId, int version, String sourceNodeId,
                          String profileId, TriggerSnapshot trigger, String dedupKey);  // sourceNodeId：命中的 Source 節點

    void signal(String tenantId, String enrollmentId, WakeSignal signal);

    void cancel(String tenantId, String enrollmentId, String reason);   // G10

    void cancelAll(String tenantId, String journeyId, String reason);   // G10 旅程層

    Optional<EnrollmentState> get(String tenantId, String enrollmentId);
}
```

```java
public interface StateStore {
    EnrollmentState create(EnrollmentState state) throws DuplicateEnrollmentException;
    Optional<EnrollmentState> findById(String tenantId, String enrollmentId);
    List<EnrollmentState> lockDue(Instant now, int limit);
    List<EnrollmentState> findWaitingForEvent(String tenantId, String profileId, String eventName);
    Optional<EnrollmentTrace> findOpenTrace(String tenantId, String enrollmentId);  // 等待節點喚醒時沿用其 seq
    void save(EnrollmentState state, EnrollmentTrace trace, List<Effect> effects);  // 單一交易
}
```

```java
public interface Timer {
    void schedule(String enrollmentId, Instant wakeAt);
    void cancel(String enrollmentId);
    void cancelAll(String tenantId, String journeyId);
}
```

```java
public interface EffectExecutor {
    void execute(String tenantId, String enrollmentId, Effect effect);
    void cancelPending(String tenantId, String enrollmentId);           // G10
    void cancelPendingByJourney(String tenantId, String journeyId);     // G10
}
```

> **`JourneyRuntime` 介面上不得出現 `EnrollmentRecord`、`next_wake_at`、任何 SQL 型別。**
> 這是 `02-enrollment-model.md` 裁決 2 的落地檢查點。

### `findWaitingForEvent` 必須帶 `profileId`

事件喚醒要找的是「**這位客戶的、在等這個事件的**」Enrollment，不是「所有在等 `order_completed` 的前 N 筆」。少了 `profileId`：

- 語意錯誤——回傳的多半是別人的 Enrollment，還要在應用層再篩一次
- 效能崩潰——租戶有 50 萬人在等 `order_completed` 時，索引形同全掃

同一位客戶在同一條事件上最多只會有少量 Enrollment（受 G3 去重約束），因此**不需要 `limit`**。對應索引見 `02-enrollment-model.md` §9。

---

## 8. 資料抽象（換規模、換資料源時替換）

```java
public interface ProfileStore {
    String resolve(String tenantId, EventIdentity identity);
    Map<String,Object> getFields(String tenantId, String profileId, Set<String> fields);
    void applyOperations(String tenantId, String profileId, List<ProfileOperation> ops);
}

public interface SegmentEngine {
    boolean isMember(String tenantId, String profileId, String segmentId);
    Map<String,Boolean> isMemberBatch(String tenantId, String profileId, Set<String> segmentIds);
    Object evaluate(String tenantId, String profileId, BehaviorQuery query);
    Stream<String> materialize(String tenantId, String segmentId);
    long estimateCount(String tenantId, ConditionTree tree);
}

public interface EventStream {
    void publish(NormalizedEvent event);
    void subscribe(String tenantId, EventHandler handler);
}

// ChannelAdapter 見 05-delivery-pipeline.md
```

---

## 9. ArchUnit 強制規則

放在 `src/test/java/.../ArchitectureTest.java`，**CI 必跑**。這是換路保障的最後一道防線。

| # | 規則 |
|---|---|
| 1 | 實作 `JourneyNode` 的類別，**不得**有任何欄位（必須無狀態） |
| 2 | 實作 `JourneyNode` 的類別，**不得**依賴 `..repository..`、`..jdbc..`、`javax.sql..`、`..service..` |
| 3 | `NodeContext`、`NodeResult`、`EnrollmentState` 的欄位型別，限 record／enum／`java.time`／集合／基本型別 |
| 4 | `JourneyRuntime` 的方法簽章，**不得**出現 `..persistence..` 套件的型別 |
| 5 | `..engine.node..` 套件**不得**呼叫 `Instant.now()`、`LocalDate.now()`、`System.currentTimeMillis()` |
| 6 | `..engine.node..` 套件**不得**依賴 `..channel..`（節點不認識通道） |

> 規則 1 看似嚴苛，但它保證節點可安全共用單一實例，也讓「節點偷藏依賴」變成編譯後立刻被抓到的事。

---

## 10. 節點實作範例

```java
public final class ConditionNodeImpl implements JourneyNode {

    @Override public String type() { return "ConditionNode"; }

    @Override
    public DataRequirements requirements(NodeConfig config) {
        ConditionTree tree = config.get("condition", ConditionTree.class);
        return DataRequirements.from(tree);   // 從條件樹推導要查什麼
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        ConditionTree tree = ctx.config().get("condition", ConditionTree.class);

        // 只用 ctx 裡已預查好的資料求值，不查任何外部資源
        Evaluation eval = ConditionEvaluator.evaluate(tree, ctx);

        return NodeResult.advance(
            eval.matched() ? "yes" : "no",
            eval.explain(),      // decisionReason：人可讀的理由
            eval.values()        // evaluated：實際取到的值
        );
    }

    @Override
    public ValidationResult validate(NodeConfig config, JourneyDefinition journey) {
        return Validators.requirePorts(config, journey, "yes", "no")
                         .and(Validators.requireCondition(config, "condition"));
    }
}
```

---

## 11. 驗收條件

- [ ] 全部 6 條 ArchUnit 規則在 CI 中通過
- [ ] `JourneyNode` 實作類別中不存在任何欄位（規則 1）
- [ ] 節點程式碼中不存在 `Instant.now()`（規則 5）
- [ ] 在「狀態已提交、Effect 尚未執行」時強制中斷程序，重啟後訊息**恰好送出一次**
- [ ] 連續 51 個非等待節點的旅程，在第 50 個被中止並轉 `FAILED`
- [ ] `WaitForEventNode` 在事件先到時走 `occurred`、逾時先到時走 `timeout`，且另一個觸發器被取消
- [ ] 每個 `NodeResult` 的 `decisionReason` 非空且非常數字面值
- [ ] 含等待節點的迴圈旅程，同一個 `SendMessageNode` 走第二圈時**訊息確實再送出一次**（`visitSeq` 生效）
- [ ] 單一節點回傳兩個 Effect 時，兩者的冪等鍵不同且**都被執行**（`effectIndex` 生效）
- [ ] 節點程式碼中不存在自行拼接的冪等鍵字串
