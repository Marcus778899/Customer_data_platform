# development — 本機開發環境

## 啟動

```bash
cd development
cp .env.example .env
docker compose up -d
```

## 服務

| 服務 | 位置 | 用途 |
|---|---|---|
| Postgres | `localhost:5432` | 主資料庫 |
| Redis | `localhost:6379` | 頻率上限計數、分散式鎖 |
| Mailhog SMTP | `localhost:1025` | 本機收信，避免測試信真的寄出 |
| Mailhog UI | http://localhost:8025 | 看收到的信 |

## 常用

```bash
docker compose ps          # 狀態
docker compose logs -f     # 日誌
docker compose down        # 停止
docker compose down -v     # 停止並清空資料
```

資料存放於 `development/data/`，已被 `.gitignore` 排除。

---

## 測試用的資料庫不是這一套

`mvn test` 不會用到上面的 compose。後端測試（如 `CoreTablesMigrationTest`）由 **Testcontainers** 自己拉一個乾淨的 Postgres 容器、跑完 Flyway、測完銷毀，跟你手動起的這套互不干擾（它會綁隨機 port）。

所以：

- 跑測試**不需要**先 `docker compose up`
- 但**需要 Docker daemon 在跑**——Testcontainers 只是自動化了起容器，不是繞過 Docker
- 這套 compose 是給你手動連進去看資料、試 SQL 用的

---

## 排錯

### `Could not find a valid Docker environment`

**這句話是誤導的。** 多數情況下 Docker 是好的，真正的原因是 **Docker API 版本不相容**：

docker-java（Testcontainers 底層）預設以 **API 1.32** 連線，而 **Docker Engine 29+ 最低要求 1.40**，直接回 `400`。Testcontainers 把這個 400 轉述成「找不到 Docker 環境」，於是你會往端點、pipe、防火牆的方向去找，全都是死路。

先確認引擎其實是好的：

```bash
docker version          # Server 有值就代表引擎正常
```

再確認是不是 API 版本問題（Windows 需先在 Docker Desktop 開啟 TCP 端點才能這樣測；Linux/Mac 用 `curl --unix-socket /var/run/docker.sock`）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:2375/v1.32/info   # 400 → 就是它
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:2375/v1.44/info   # 200
```

**修正已經在 `backend/pom.xml` 裡，不需要你做任何事**：

| 設定 | 作用 |
|---|---|
| `<testcontainers.version>1.21.3</testcontainers.version>` | 覆寫 Spring Boot 3.3.5 管的 1.19.8 |
| surefire 的 `<api.version>1.44</api.version>` | 指定連線用的 API 版本 |

> `api.version` **必須由系統屬性傳遞**。寫進 `~/.testcontainers.properties` 不生效——這點沒有文件寫，是實測出來的。

這個修正對 CI 也是必要的：GitHub Actions 目前的 Docker 還支援舊 API，等它們升到 29，沒有這兩處設定就會整批紅掉。

### Windows：Testcontainers 連到錯的 named pipe

症狀是同一個 400 錯誤，但就算修好 API 版本仍然連不上。兩個成因：

**① 兩條 pipe 打架。** `docker context ls` 若顯示 `desktop-linux` 是 current，引擎在 `dockerDesktopLinuxEngine`；但舊的 `docker_engine` pipe 仍會回應，只是回一包空殼（可由回應中的 `com.docker.desktop.address` label 認出來）。

**② `~/.testcontainers.properties` 會快取失敗的 strategy。**

```
docker.client.strategy=org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy
```

這一行會讓 Testcontainers 優先試 npipe，**並且蓋掉你設的 `DOCKER_HOST`**。刪掉整個檔案即可，它會自動重建：

```powershell
Remove-Item "$env:USERPROFILE\.testcontainers.properties"
```

> 若要在該檔指定端點，注意 PowerShell 5.1 的 `-Encoding utf8` 會寫入 **BOM**，會讓第一個 key 解析失敗且無任何錯誤訊息。用 `[System.IO.File]::WriteAllText()` 搭配無 BOM 的 UTF8Encoding。

### `class file version 65.0 ... only recognizes up to 61.0`

`target/` 裡混進了 JDK 21 編譯的 class，但 Maven 跑在 JDK 17（見 `pom.xml` 的 `<java.version>17</java.version>`）。通常是 IDE 的 project SDK 沒對齊，在背景把 21 的產物寫回 `target/`。

暫時繞過：

```bash
mvn clean test
```

根治：把 IDE 的 project SDK / Gradle-Maven JDK 改成 17。

### Windows 的 shell 差異

`.github/workflows/ci.yml` 與多數文件的指令是 bash。在 PowerShell 要換寫法：

| bash | PowerShell |
|---|---|
| `export FOO=bar` | `$env:FOO = 'bar'` |
| `FOO=bar cmd` | `$env:FOO = 'bar'; cmd` |
| `$FOO` | `$env:FOO` |

另外 Git Bash 會把 `npipe:////./pipe/...` 當成路徑做轉換而弄壞它，要設這類值請用 PowerShell。

### 行尾

`.gitattributes` 設定 `* text=auto eol=lf`，commit 時會自動正規化，工作區是 CRLF 不影響。pre-commit 的 `mixed-line-ending --fix=lf` 會直接改檔案然後回報 failed——那是正常行為，`git add` 後再 commit 一次即可。
