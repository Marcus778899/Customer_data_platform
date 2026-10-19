# CDP 開發規格（Phase 0：契約先行）

本目錄是**實作規格**，供開發者與 AI 協作時直接引用。
上游是骨幹計劃書（不在本 repo：與本 repo 同層的 `CDP/docs/plan/backbone-plan.md`，見 `docs/design/INIT-01.md`）。

**衝突時以本目錄為準。** 計劃書是討論產物，spec 是實作依據。

---

## 規格清單

| # | 檔案 | 內容 | 對應補強項 | 狀態 |
|---|---|---|---|---|
| 00 | `README.md` | 索引、裁決、共通約定 | — | ✅ |
| 01 | `01-event-contract.md` | 事件契約（三種來源） | G2 | ✅ |
| 02 | `02-enrollment-model.md` | Enrollment 資料模型 | G1, G3, G4 | ✅ |
| 03 | `03-journey-definition.md` | 旅程定義格式、節點型別 | G6 | ✅ |
| 04 | `04-node-contract.md` | 節點執行介面、引擎抽象 | — | ✅ |
| 05 | `05-delivery-pipeline.md` | 發送管線、守門、取消點 | G9, G10 | ✅ |
| 06 | `06-segment-dsl.md` | 受眾條件語言 + 資料字典 | — | ✅ |
| 07 | `07-database-schema.md` | 資料表總覽、型別對照、落地決策 | — | ✅ |

### 建議閱讀順序

不必從頭讀到尾。依你當下要做的事挑：

| 你要做什麼 | 讀這些就夠 |
|---|---|
| **搞懂整體形狀** | 計劃書第六節（分期）→ 第七節（技術選型）→ 本檔「四項裁決」 |
| **開始寫引擎** | `04`（節點契約與引擎抽象）→ `02`（Enrollment 模型）→ `03` §6（圖驗證） |
| **接資料進來** | `01`（事件契約）→ `06` §1（資料字典） |
| **接通道出去** | `05`（發送管線全篇） |
| **做畫布 / 前端** | `03`（旅程定義格式）→ `06` §2~§6（條件樹）→ `06` §9（篩選器 UI） |
| **寫 migration / 建表** | `07`（資料表總覽，含各表語意正本的指路） |

每份 spec 的**末節都是驗收條件**——趕時間時先讀那一節，它等於該份規格的重點摘要。

---

## 四項裁決

計劃書內部有數處矛盾，在此裁決。**以下為最終定義。**

### 裁決 1：Consent 守門依訊息類型分流

計劃書 4.4 第 4 條寫「退訂後任何路徑都不得送出」，與 G9（交易型訊息不受約束）直接衝突。

**採用 G9。** 4.4 第 4 條改寫為：

> **發送前的三道守門，套用範圍由「訊息類型」決定：**
>
> | 訊息類型 | Consent | 頻率上限 | 靜音時段 |
> |---|---|---|---|
> | `TRANSACTIONAL` 交易型 | 不套用 | 不套用 | 不套用 |
> | `MARKETING` 行銷型 | **套用** | **套用** | **套用** |
>
> 訊息類型綁在 **MessageTemplate** 上，不在節點上。變更類型需經審核流程。

理由：退訂客戶仍須收到訂單確認、驗證碼；反之若讓類型可隨意設定，平台會變成繞過退訂的工具。綁在模板 + 審核，是兩邊都能守住的位置。

### 裁決 2：Enrollment 分「契約層」與「實作層」

計劃書 4.3 讓 `next_wake_at` 出現在 Enrollment 定義中，與 7.3「不可滲進契約層」衝突；且 4.3 遺漏 G1/G3/G4 三個欄位。

**分成兩層：**

- **`EnrollmentState`（契約層）** — 引擎對外的唯一模型。用 `wakeCondition` 表達「在等什麼」，不暴露任何喚醒實作細節
- **`EnrollmentRecord`（實作層）** — 自建路線的資料表。`next_wake_at` 只存在於此，供推進迴圈的 `lockDue`（partial index 掃到期列）使用；不用 db-scheduler（`docs/design/INIT-01.md` R1）

規則：**任何 `JourneyRuntime` 介面上的型別，不得引用 `EnrollmentRecord`。**

詳見 `02-enrollment-model.md`。

### 裁決 3：抽象層分兩類

計劃書 4.5 與 7.3 各列一組介面，互不涵蓋。**合併為兩類，職責不重疊：**

| 類別 | 介面 | 什麼情況會換 |
|---|---|---|
| **引擎抽象** | `JourneyRuntime`、`StateStore`、`Timer`、`EffectExecutor` | 自建 → Temporal |
| **資料抽象** | `EventStream`、`ProfileStore`、`SegmentEngine`、`ChannelAdapter` | 規模成長、換資料源、加通道 |

兩類都不可被節點邏輯直接依賴（見裁決 4）。

### 裁決 4：節點不得持有任何依賴

為保住換 Temporal 的路（計劃書 7.3 的五條禁令），節點執行介面定為：

```java
NodeResult execute(NodeContext context);
```

`NodeContext` 是**純資料**——已查好的 Profile 快照、觸發快照、旅程變數、當前時間。
**節點收不到任何 Repository、Service、DataSource。** 用型別把捷徑堵死。

需要查資料的節點（如 `ConditionNode`），由引擎在呼叫 `execute` 前預先查好放進 `NodeContext`。

---

## 共通約定

### 識別碼

| 欄位 | 型別 | 說明 |
|---|---|---|
| `profileId` | `String` (ULID) | 客戶單一視圖識別碼 |
| 其餘 id | `String` (ULID) | 排序友善、可讀性優於 UUID |

### 時間

- 儲存一律 **UTC**，型別用 `java.time.Instant`
- 呈現與「客戶當地時間」計算才轉時區
- **禁止 `Instant.now()` / `LocalDate.now()` 等直接取時間**。一律注入 `java.time.Clock`（計劃書 7.5 ②）
- 期間表示用 ISO-8601 Duration（`P3D`、`PT1H`）

### 租戶（單一租戶，D-051、D-063）

一套環境一家公司：實體、資料表、介面與 API 都**不帶** `tenantId`／`tenant_id`（V5 移除既有欄位，`07` §8）。

#### 回到多租戶時的做法（不排期）

本節與下一小節（RLS、`journey_engine`、雙連線池）保留為回到多租戶時的做法，順序不可對調：

1. 新 migration 為每張表加回 `tenant_id` 並回填所有歷史資料，重建含它的 PK（`event`）與索引（D-063）；`04`～`06` 介面加回 `tenantId` 參數
2. 資料庫層開啟 **PostgreSQL Row Level Security**，以 session 變數 `app.tenant_id` 為準（`07` §2）
3. 應用層所有查詢帶 `tenantId`，RLS 是最後一道防線不是唯一一道

#### RLS 的唯一例外：`journey_engine`

（回到多租戶後）推進迴圈的 `StateStore.lockDue(now, limit)` **必須跨租戶掃描**——它的工作就是找出「所有到期的 Enrollment」，沒有 `tenantId` 可帶。RLS 開著時這個查詢回傳空集合，計時器一則都推不動。

因此有一個跨租戶的 `NOLOGIN` role `journey_engine`（role 設計依 `docs/design/INIT-01.md` R2）。它**不**帶 `BYPASSRLS`（PG 18 建立需 superuser，`docs/research/v0.2.0-design-questions.md` Q1），而是由每張 RLS 表的 policy `engine_all`（`TO journey_engine USING (true)`）放行，見 `07` §2。應用程式用同一個登入帳號（環境變數 `CDP_DB_APP_USER`：`NOINHERIT`、非 superuser、非表擁有者）開兩個連線池，各自在**連線初始化**時 `SET ROLE`：

| 元件 | 連線池 → role |
|---|---|
| 旅程推進迴圈、事件派發、outbox relay、發送 consumer、旅程停止的背景清理 | 引擎池 → `journey_engine`（`engine_all` policy 放行全部租戶） |
| API 層、客服查詢、客戶搜尋、其他一切 | 應用池 → `cdp_app`（受 RLS 保護；每個交易開頭 `set_config('app.tenant_id', …, true)`，未設定 = 讀不到任何列） |
| Flyway migration | 表擁有者帳號（`POSTGRES_USER`），應用程式執行期不用 |

> 🚫 **這個 role 的連線池不得被任何其他元件共用。** 共用一次，RLS 就形同虛設——
> 而 RLS 存在的目的正是計劃書第五節那句「讓遺漏成為不可能，而非靠自律」。
> 建立引擎連線池時，`SET ROLE` 要寫在連線初始化，不是每次查詢前才想起來；誰可以拿引擎池由 `ArchitectureTest` 限制。

### 命名

- Java 型別、欄位：`camelCase` / `PascalCase`
- JSON 對外契約欄位：`snake_case`
- 資料表與欄位：`snake_case`
- 節點型別字串：`PascalCase`（與 Java 類別同名，如 `WaitForEventNode`）

### 冪等

所有會產生外部可見副作用的操作，必須帶 **冪等鍵（idempotencyKey）**：

```
{enrollmentId}:{nodeId}:{visitSeq}:{effectIndex}
```

| 片段 | 來源 | 為什麼需要它 |
|---|---|---|
| `enrollmentId` | Enrollment | 區隔不同客戶的同一條旅程 |
| `nodeId` | 旅程定義 | 區隔同一次執行中的不同節點 |
| `visitSeq` | `EnrollmentTrace.seq`（見 `02-enrollment-model.md` §8） | **區隔合法迴圈的第 N 次重訪**。旅程允許含等待節點的迴圈，同一個 `SendMessageNode` 第二圈仍須真的發出 |
| `effectIndex` | `NodeResult.effects` 的陣列索引 | 區隔**同一次執行回傳的多個 Effect**（發券 Webhook + 發訊息不可共用同一把鍵） |

儲存層以此為唯一鍵，重放不重複執行。

> **重試沿用同一把鍵，不另開維度。** 「重試」的定義就是撞同一把鍵而被去重——若讓重試次數進入鍵值，重試反而會產生第二則訊息，冪等保證直接失效。

---

## 已裁決事項（Phase 0 收尾）

六題全數定案。保留原題目與裁決結果，是因為**它們每一條都是「規格讀起來沒問題、實作時才會撞牆」的那種洞**——日後改動相關契約前，先看一眼當初為什麼這樣定。

| # | 題目 | 影響哪份 spec |
|---|---|---|
| ~~1~~ | ~~`settings.goal` 與 `GoalNode` 誰優先~~ **已裁決**：`goal` 持續評估、優先於一切節點；`GoalNode` 降為顯式標記 | `03` §3、`02` §3、`04` §6 |
| ~~2~~ | ~~5 分鐘仲裁窗的觸發時機~~ **已裁決**：配額充足直接放行零延遲，reserve 失敗才進窗排隊 | `05` §3②、§4 |
| ~~3~~ | ~~`lockDue` 跨租戶掃描 vs RLS~~ **已裁決**：`journey_engine` NOLOGIN role＋每表 `engine_all` policy（不用 BYPASSRLS；D-051、D-063 後不排期），見共通約定 | `README`、`04` §7 |
| ~~4~~ | ~~Redis 不可用時 kill switch 讀不到~~ **已裁決**：降級查 Postgres 的 `journey.status`，不放行 | `05` §5、`plan` 7.2.1 |
| ~~5~~ | ~~遲到事件能否喚醒既有 Enrollment~~ **已裁決**：門檻只約束「建立新 Enrollment」，喚醒照做 | `01` §6、`02` §4 |
| ~~6~~ | ~~`journey-definition.schema.json` 尚未產出~~ **已完成**：schema 涵蓋全部 22 種節點型別，三層分工寫進 `03` §6，三個場景 fixture 與驗收測試皆已通過 | `03` §6 |

---

## Phase 0 驗收條件

1. ✅ 附錄 C 三個場景（棄單挽回、生日禮、沉睡喚醒）都能用本目錄的契約完整表達
2. ✅ 三個場景的旅程定義 JSON 通過 `journey-definition.schema.json` 驗證
3. ✅ 三種事件來源（客戶行為／外部系統回寫／內部操作）都能用同一份事件契約描述

**三條全部通過，Phase 0 的契約層收尾。** 執行方式：`cd backend && mvn test`（18 個測試）。

| 驗收 | Schema | Fixture | 測試 |
|---|---|---|---|
| 1、2 | `schema/journey-definition.schema.json` | `test/resources/journey/scenarios/`（3 個場景） | `JourneyDefinitionSchemaTest` |
| 3 | `schema/event.schema.json` | `test/resources/event/sources/`（3 種來源 + 回流事件） | `EventContractSchemaTest` |

> **建立 fixture 的過程逼出四個契約缺口**（`global_exit` 的 per-condition `on_match`、`WaitUntilNode` 的 `DYNAMIC.time`、`WITHIN_NEXT` 運算子、`FilterNode.exit_reason` 必填），皆已補進 spec——
> 這正是「用真實場景壓測契約」的價值：光讀規格看不出來，寫出一份完整的旅程才會撞到。
>
> **驗收通過不等於 Phase 0 結束**：上面「未決事項」的五題仍然開著，它們會改動已通過的契約。
