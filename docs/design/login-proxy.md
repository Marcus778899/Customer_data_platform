# 設計：登入限速的來源 IP 收緊（login-proxy-design）
<!-- D-091；資安 docs/security/v0.3.0.md M-1（uat F-12）、M-2。取代 DESIGN-05 做法 #5 的 clientIp 取得部分。J = api/src/main/java/com/cdp、A = api/src/test/java/com/cdp/acceptance、R = api/src/main/resources、TR = api/src/test/resources -->
- 契約：`auth.md` §3「來源 IP」（信任代理、格式、啟動檢查、比對、計數鍵、部署要求）；業務規則 `docs/domain/rules.md`「登入限速」
- 現況：`R/application.yml:49-52` 以 `server.tomcat.remoteip.internal-proxies: ${CDP_TRUSTED_PROXIES:<私有網段＋loopback>}` 讓 Spring 自動建立的 Tomcat `RemoteIpValve` 解析 `X-Forwarded-For`；`J/security/ClientAddress.of` 回 `getRemoteAddr()` 原字串（轉小寫），`LoginRateLimiter`（`:153-155`、`:185-187`）兩張計數表都以它為鍵
## 做法
| # | 決策 | 理由 |
|---|---|---|
| 1 | 新設定 `cdp.security.trusted-proxies`，`R/application.yml` 綁 `${CDP_TRUSTED_PROXIES}`（**不給預設**）；新 record `J/security/TrustedProxiesProperties`（`@ConfigurationProperties("cdp.security")`，只含 `trustedProxies`，不動 `AuthProperties` 建構子） | 未設定時 Spring 無法解析佔位符即啟動失敗；不用 Tomcat 正規式當設定格式（`.` 誤寫即放寬，無法驗證「確切位址」） |
| 2 | `J/security/TrustedProxies`（新）：`static TrustedProxies parse(String raw)` 依契約「格式」驗證，不符丟 `IllegalStateException`（訊息：變數名稱、第幾項、哪條規則；不回顯值）；`String toInternalProxiesRegex()`；`boolean isNone()`。逐項先以字元集與結構判斷是 IPv4／IPv6 文字位址，**通過後**才 `InetAddress.getByName` 取值（不可觸發 DNS 查詢）；IPv4-mapped 轉成 IPv4；由設定類別在啟動時建成 bean（非 web 的測試 context 也會驗證），#4 注入它 | 啟動檢查集中一處、可單元測試 |
| 3 | 正規式：每個位址輸出 `Inet4Address`／`Inet6Address.getHostAddress()` 的寫法（與 Tomcat `getRemoteAddr()` 同源），IPv6 另允許可選的 `%<zone>` 尾碼，各自 `Pattern.quote` 後以 `|` 串接；`-` 輸出永不符合的 `(?!)` | Tomcat 以字串比對連線端，寫法不同（`::1` 對 `0:0:0:0:0:0:0:1`）會漏配；**不可用空字串**：Spring Boot 文件明寫 `internal-proxies` 設空 = 信任全部 |
| 4 | `J/security/TrustedProxiesTomcatCustomizer`（新，`WebServerFactoryCustomizer<TomcatServletWebServerFactory>`，`Ordered.LOWEST_PRECEDENCE`）：在 Spring 自動建立的 valve 之後執行，於 `getEngineValves()` 找 `RemoteIpValve`：多於一個 → 丟例外拒絕啟動；找不到 → 記 warn 並略過（不處理 XFF，一律用連線端位址）；找到唯一一個 → 設 `setInternalProxies(regex)`、`setTrustedProxies(null)`，並在它**前面**插入內部類別 `ForwardedForNormalizer` valve：把 `remoteIpHeader`（多個標頭合併成一行）逗號分隔的每個 token 以 `TrustedProxies.literal` 判斷，是 IP 文字就改寫成 `getHostAddress()` 寫法（IPv4-mapped → IPv4），非 IP 原樣保留 | 保留 Spring 自動 valve 的其他屬性（`protocolHeader` 等），只換信任範圍。找不到不擋啟動：mock web 的測試 context 沒有 valve（也不收真請求），丟例外會讓這些 context 起不來；缺 valve 方向是 fail closed（不採信 XFF），正式啟動因 #5 的 `remote-ip-header` 一定會建 valve，回歸由 #5 基底值與 RANDOM_PORT 驗收 `A/login-proxy-api/TrustedProxiesLoopbackAcceptanceTest`（信任時採信 XFF）把關。正規化：`RemoteIpValve` 以字串比對，XFF 的 `::1` 對不上 `0:0:0:0:0:0:0:1`，落實契約 §3「比對」；同 #2 先檢查文字位址，不觸發 DNS |
| 5 | `R/application.yml`：保留 `remote-ip-header: X-Forwarded-For`（確保 valve 會被建立）；`internal-proxies` 改成常數 `"(?!)"`（失效時預設不信任任何代理，fail closed）；註解改指 `auth.md` §3 | 自訂器萬一沒套用也不會退回私有網段預設 |
| 6 | `ClientAddress.of(request)` 回「計數鍵」：解析 `getRemoteAddr()`（同 #2 先檢查是文字位址再解析）；IPv4 → 點分完整位址；IPv4-mapped → IPv4；IPv6 → 前 64 位元，格式 `<四段十六進位小寫、去前導 0>::/64`（例 `2001:db8:1:2::/64`），zone id 捨棄；無法解析 → `unknown`（同現況） | M-2；`LoginRateLimiter` 不用改 |
| 7 | `TR/application-test.yml` 加 `cdp.security.trusted-proxies: "-"`；需要用 `X-Forwarded-For` 模擬多來源的驗收以 `@SpringBootTest(properties = "cdp.security.trusted-proxies=127.0.0.1,::1")` 明確信任本機 | 測試不再靠預設信任 loopback（M-1 建議 3）；其餘以 `profiles("test")` 啟動的測試不受影響 |
| 8 | `.env.example`：`CDP_TRUSTED_PROXIES=CHANGE_ME`（必填，佔位值會被格式檢查拒絕），上一行說明寫明 `-` 的語意、只填確切位址、代理要覆寫 XFF（nginx 指令照契約） | 甲方與維運每個環境都要明確決定；本機建議值見「設定」 |
## 業務規則與術語：`rules.md` 新增「登入限速」4 條；`README.md` 核心規則加 1 行、檔案清單補「登入限速」
## 設定（寫進 .env.example，這裡不寫值）：`CDP_TRUSTED_PROXIES`（非機密，D-091 起必填）。甲方本機開發（瀏覽器→Vite dev proxy→後端，Vite 不帶 XFF；或直接連後端）建議 `-`，本機請求都以 loopback 為同一來源
## 威脅與對策
| 資產 | 威脅 | 對策 |
|---|---|---|
| 帳號（線上猜密碼）、帳號可用性 | 未登入者偽造 `X-Forwarded-For` 換來源繞過限速，或冒用他人出口 IP 鎖住整個來源（M-1；代理原樣轉送、內網用戶端、SNAT） | 無預設信任、必填且只收確切位址（#1～#5）；部署要求代理覆寫 XFF（契約 §3）；`-` 時完全不讀 XFF |
| 同上 | 一台主機輪換 IPv6 /64 內的位址（M-2） | 計數鍵截 /64（#6） |
| 設定正確性 | 誤填網段、主機名稱、正規式、佔位值，或忘了設定 | 啟動檢查拒絕（契約 §3「格式」「啟動檢查」）；不做 DNS 查詢 |
## 受影響的既有驗收測試
已 grep `A/`：`X-Forwarded-For`、`internal-proxies`、`TRUSTED_PROXIES`。以 `profiles("test")` 自起的啟動類測試（`A/security/StartupValidationAcceptanceTest`、`A/security-hardening-api/KeyStrengthStartupAcceptanceTest`、`A/ingest/DispatchPropertiesStartupAcceptanceTest`、`A/relay-loop-rewrite/RelayStartupValidationAcceptanceTest`）因 #7 取得 `-`，不受影響；`A/user-auth-api/LoginRateLimitAcceptanceTest` 不帶 XFF，結果不變
| 測試檔 | 跟新契約哪裡矛盾 | 交給哪張單改寫 |
|---|---|---|
| `A/security-hardening-api/LoginSourceLimitAcceptanceTest` | 靠預設信任 loopback 以 XFF 模擬多來源；改為 `-` 後全部算同一來源 → 加 `cdp.security.trusted-proxies=127.0.0.1,::1`，類別說明改寫 | login-proxy-api |
| `A/security-hardening-api/LoginGlobalConcurrencyAcceptanceTest` | 同上（各請求不同 XFF 避開來源鎖定） | login-proxy-api |
| `A/security-hardening-api/LoginUntrustedProxyAcceptanceTest` | 以 `server.tomcat.remoteip.internal-proxies=192\.0\.2\.1` 設信任範圍，該屬性不再是設定入口（#5）→ 改 `cdp.security.trusted-proxies=192.0.2.1` | login-proxy-api |
## 檔案分工
| 檔案 | 負責 |
|---|---|
| `J/security/TrustedProxies`、`TrustedProxiesProperties`、`TrustedProxiesTomcatCustomizer`（皆新）、`ClientAddress`、`R/application.yml`（`server.tomcat.remoteip`、`cdp.security.trusted-proxies`）、`TR/application-test.yml`、`.env.example`、單元測試 `api/src/test/java/com/cdp/security/ClientAddressTest`、`TrustedProxiesTest`（新）、`TrustedProxiesTomcatCustomizerTest`（新） | login-proxy-api（backend） |
| 上表三個既有驗收測試、`A/login-proxy-api/`（新） | qa（依新契約改寫後 lock） |
## 派工順序
| task_id | 角色 | 內容 | 驗收目錄 | 相依／平行 |
|---|---|---|---|---|
| login-proxy-api | backend | 做法 #1～#8；`auth.md` §3「來源 IP」全部列。驗收建議：未設／`CHANGE_ME`／網段／主機名稱／`-` 與位址混用 → 啟動失敗且訊息含變數名稱；`-` 時 loopback 帶 XFF 不採信（換 XFF 仍被鎖）；信任 `127.0.0.1,::1` 時 XFF 由右往左取第一個非信任位址；同一 /64 內不同 IPv6（XFF 模擬）共用來源計數、不同 /64 不共用；IPv4-mapped 與 IPv4 同一來源 | `A/login-proxy-api/`（另含上表三檔） | 無；可與任何前端單平行。合併後 security 重審 M-1／M-2、uat 重測 F-12 |
- 不另開整合單（純後端，無介面變化）；甲方本機 `.env` 須在合併前加 `CDP_TRUSTED_PROXIES`（建議 `-`），否則 `make api-run` 起不來
## 自行決定（可逆、在範圍內；甲方核可設計時一起看，要改再說）
| 項目 | 決定 |
|---|---|
| 設定格式 | 逗號分隔的確切 IP 清單，取代 Tomcat 正規式；不收網段（D-091「只填確切位址」）、不收主機名稱（避免啟動時 DNS 與 DNS 欺騙）；上限 10 項 |
| `-` 與 `CHANGE_ME` | `.env.example` 寫 `CHANGE_ME` 讓甲方必須改；`-` 是唯一的「不信任」寫法，空字串視為未設定 |
| IPv6 計數鍵格式 | `<前四段>::/64`，只在記憶體內當鍵，不對外 |
| 啟動錯誤訊息 | 寫變數名稱、第幾項、哪條規則，不回顯值（與 §1 金鑰檢查一致） |
## 待甲方決定／需要調查：無（D-091 已定，收緊不需 D-編號；DESIGN-05 需調查 2 由 D-091 收斂，各環境的值由維運依 `auth.md` §3 填）
## 風險
| 風險 | 對策 |
|---|---|
| 既有環境升版後因未設 `CDP_TRUSTED_PROXIES` 起不來 | 發版說明列出；錯誤訊息指出變數；甲方本機先加 |
| 有代理卻填 `-`：所有使用者共用代理位址，30 次錯誤即鎖住所有人（契約「來源鎖定」） | `.env.example` 說明寫明有代理必須填其位址；uat 以實際部署拓樸驗 |
| 代理位址會變（容器重建換 IP） | 只收確切位址是 D-091 的取捨；部署時固定代理 IP（例 compose 固定 `ipv4_address`），寫進 `.env.example` 說明 |
| 自訂器與 Spring 自動 valve 的順序在升級後改變 | #5 基底值 fail closed；`TrustedProxiesTomcatCustomizerTest` 與驗收「信任清單內才採信 XFF」雙重把關 |
