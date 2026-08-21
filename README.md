# CDP — Customer Data Platform

客戶資料平台 / 行銷自動化平台。核心能力：**圈受眾 → 走旅程 → 發推播 → 收成效**。

## 專案結構

```
CDP/
├─ frontend/      React + TypeScript + Vite
├─ backend/       Spring Boot 3 + Java 21 + PostgreSQL
└─ development/   本機開發環境（docker-compose）
```

> 開發規格保存於本機 `docs/`，**不進版控**。需要者請另行索取。

## 環境需求

| 工具 | 版本 |
|---|---|
| JDK | 21 |
| Node.js | 20+ |
| Docker | 含 Compose v2 |
| Python | 3.8+（僅 pre-commit 需要） |

## 起手

```bash
# 1. 本機基礎設施
cd development && cp .env.example .env && docker compose up -d && cd ..

# 2. 後端
cd backend && mvn spring-boot:run        # http://localhost:8080

# 3. 前端
cd frontend && npm install && npm run dev # http://localhost:5173

# 4. pre-commit
pip install pre-commit
pre-commit install --install-hooks
pre-commit install --hook-type commit-msg
```

## 常用指令

| 位置 | 指令 | 說明 |
|---|---|---|
| `backend/` | `mvn verify` | 建置與測試 |
| | `mvn spotless:apply` | 套用格式 |
| `frontend/` | `npm run dev` | 開發伺服器 |
| | `npm run lint` | ESLint |
| | `npm run typecheck` | 型別檢查 |
| | `npm run format` | Prettier |
| 根目錄 | `pre-commit run --all-files` | 全檔檢查 |

## Commit 規範

採 [Conventional Commits](https://www.conventionalcommits.org/)，由 pre-commit 於 `commit-msg` 階段強制。

```
feat(journey): 加入 WaitForEventNode
fix(delivery): 修正頻率上限計數時區
docs(spec): 更新事件契約
```

可用型別：`feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore` `revert`

## 授權

專有軟體，保留一切權利。見 [LICENSE](LICENSE)。
