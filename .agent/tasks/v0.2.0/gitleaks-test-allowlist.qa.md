PASS f4d798e

# QA gitleaks-test-allowlist (PR #122)

執行檔：scratchpad 的 gitleaks 8.30.1（與 ci security 腳本的 GITLEAKS_VERSION = "8.30.1" 相同）；zip sha256 d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e，與回報所列一致。

1. 變更範圍：`git diff --stat develop...HEAD` 只有 `.gitleaks.toml`（+18）。符合。
2. 設定內容：`[extend] useDefault = true`；單一 `[[allowlists]]`：targetRules=["generic-api-key"]、condition="AND"、regexTarget="secret"、regexes=`^[a-z]+-SECRET-[0-9a-z]{4}$`、paths=`^api/src/test/`；附理由與調查報告路徑。三條件為 AND 且錨定，夠窄；沒有排除整個路徑或整條規則。
3. 與 CI 相同指令，在 worktree（HEAD f4d798e，262 commits）：
   - 有 .gitleaks.toml：`INF no leaks found`，exit=0
   - 只用預設規則（--config 指向只有 useDefault 的暫存檔）：`WRN leaks found: 1`，exit=1；JSON 報告僅 1 筆：RuleID generic-api-key、StartLine 32、Commit f50659a35aa7842bd683f177d84f6a50ce8cdf34、Fingerprint `f50659a35aa7842bd683f177d84f6a50ce8cdf34:api/src/test/java/com/cdp/acceptance/security/SecretToStringAcceptanceTest.java:generic-api-key:32`。恰為 f50659a 那一筆。
4. 自建反例（$TMPDIR 風格暫存 repo，放 scratchpad，已刪；每檔一行 `String secret = "<值>";`，用本 PR 的 .gitleaks.toml 掃）：
   - 對照 test/CtlTest `ingest-SECRET-7f3e`：預設規則抓到、加設定後放過（符合預期）
   - 亂數值 test/RandTest（32 字元 base62）：仍被抓
   - 同格式放 src/main（Main.java）：仍被抓
   - 值多尾巴 test/TailTest `ingest-SECRET-9x8y-q7Zk`：仍被抓
   - 額外：大寫開頭 `Ingest-SECRET-7f3e`（CaseTest）：仍被抓
   預設規則下以上 5 檔全被抓；加設定後只有對照組被放過。

額外觀察（不影響判定）：調查報告第 4 節的反例 3 若尾巴用 `-real`，連預設規則都不抓（回報 open_questions 已提），報告可更正。
密鑰值未寫入本檔（僅列虛構格式範例）。
