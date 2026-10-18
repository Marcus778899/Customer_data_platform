# INIT-01 調查（查詢日 2026-10-18）
**結論**
1. Spring Boot 3.5 OSS 已於 2026-06-30 結束（商業至 2032-06-30）；4.0 OSS EOL 2026-12-31；4.1 OSS EOL 2027-07-31。4.1.1 存在（2026-08-20），為 4.1 線最新 patch。
2. Spring Boot 4.1.1 最低 Java 仍為 17（原文 "requires at least Java 17"）；3.5.x 的 Java 需求查不到。
3. Testcontainers 1.x 無 Docker 29 修正（上游只修 2.x）；2.0.5 為 Maven Central 最新。

## 1. Spring Boot 支援期（來源 https://endoflife.date/spring-boot）
| 線 | 發布 | OSS EOL | 商業 EOL | 最新 patch（發布日） |
|---|---|---|---|---|
| 4.1 | 30 Jun 2026 | 31 Jul 2027 | 31 Jul 2028 | 4.1.1 (20 Aug 2026) |
| 4.0 | 30 Nov 2025 | 31 Dec 2026 | 31 Dec 2027 | 4.0.8 (20 Aug 2026) |
| 3.5 | 31 May 2025 | 30 Jun 2026 | 30 Jun 2032 | 3.5.16 (25 Jun 2026) |
- 4.1.1 公告 https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/ ；3.5.16 公告 https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/
- spring.io/projects/spring-boot#support 無法讀出支援表，上表未取得官方原文表。
- Java 原文：「Spring Boot 4.1.1 requires at least Java 17 and is compatible with versions up to and including Java 26. Spring Framework 7.0.9 or above is also required.」https://docs.spring.io/spring-boot/system-requirements.html
- 4.0 系統需求頁（4.0.3）列 Java 17 起、最高 25（僅搜尋摘要）：https://docs.spring.io/spring-boot/4.0/system-requirements.html

## 2. Testcontainers 與 Docker 29
- 最新 2.0.5：https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/maven-metadata.xml ；https://java.testcontainers.org/
- 問題：Docker 29.0.0 將最低 API 由 1.24 提高到 1.44，Testcontainers 1.x 預設協商較舊版本而失敗。
- Spring Boot #48104（2025-11-19 關閉）：「the Testcontainers project seems not to be releasing a fix for the 1.x release train, only for 2.x」；Spring Boot 4.x（Testcontainers 2.x）「would not be affected」。https://github.com/spring-projects/spring-boot/issues/48104
- 2.0.3 release note：「Fix compatibility with older Docker Engines by falling back to v1.32」；2.0.4 升 docker-java 3.7.1；2.0.5 無 Docker 說明。https://github.com/testcontainers/testcontainers-java/releases
- 社群繞法（非官方）：`docker-java.properties` 設 `api.version`（建議 1.44；另稱可能需 1.43/1.45）https://intellij-support.jetbrains.com/hc/en-us/community/posts/7763242546066/comments/7780415114898 ；`System.setProperty("api.version","1.44")`（JVM 全域）或 daemon 端 `DOCKER_MIN_API_VERSION=1.24`（同 #48104）；放寬 daemon.json https://forums.docker.com/t/testcontainer-stopped-working-after-updating-docker-desktop-to-v4-56-0/150823/2
- 官方 java.testcontainers.org 未提 Docker 29 或 api.version：查不到官方說明；Windows Docker Desktop 專屬說明：查不到。
- 單一未證實來源稱 Docker 29.3 將最低降為 1.40：https://oneuptime.com/blog/post/2026-08-11-woodpecker-docker-engine-29-client-version-too-old/markdown

## 3. 其他版本（查詢日）
| 項目 | 查到的版本（原文數字） | 來源 |
|---|---|---|
| React 19 | latest 19.3.1（14 Oct 2026）；EOL 未列 | https://endoflife.date/react |
| TypeScript | latest 7.0.2；6.0.3 存在（Apache-2.0） | https://registry.npmjs.org/-/package/typescript/dist-tags |
| typescript-eslint | TS「>=4.8.4 <6.1.0」；ESLint「^8.57.0 \|\| ^9.0.0 \|\| ^10.0.0」 | https://typescript-eslint.io/users/dependency-versions/ |
| Vite | latest 8.3.4 | https://registry.npmjs.org/-/package/vite/dist-tags |
| Vitest | latest 5.0.5；engines「^22.12.0 \|\| ^24.0.0 \|\| >=26.0.0」 | https://registry.npmjs.org/vitest/latest |
| ESLint | latest 10.13.0；engines「^20.19.0 \|\| ^22.13.0 \|\| >=24」 | https://registry.npmjs.org/eslint/latest |
| PostgreSQL | 18.6，Final 2030-11-14；19 RC 1 於 2026-10-15 | https://www.postgresql.org/support/versioning/ |
| Maven 3.9.x | 3.9.16（17 May 2026）；4.0.0-rc-7 為預覽，官方標「NOT safe for production」 | https://endoflife.date/maven ；https://maven.apache.org/download.cgi |
| Temurin 17 LTS | 17.0.20.1+1（19 Aug 2026）；EOL 31 Oct 2027 | https://endoflife.date/eclipse-temurin |
| Temurin 25 LTS | 25.0.4.1+1（19 Aug 2026）；EOL 30 Sep 2031 | 同上 |
- 差異：npm latest 的 TypeScript 為 7.0.2；typescript-eslint 上限 `<6.1.0`，不含 7.x（該頁未標日期）。
- 查不到：Vite 8、Testcontainers、PostgreSQL 的 endoflife.date 頁（404）；Vite/Vitest 的 EOL 政策。
