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
