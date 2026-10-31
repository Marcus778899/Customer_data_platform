# 開發指令入口。技術棧、目錄與測試慣例見 docs/design/INIT-01.md。
# 需在 Git Bash（Windows）與 Linux（CI）都能執行；GNU Make 3.81 相容。
# recipe 內只用 ASCII：Windows 版 make 會以系統 codepage 轉碼指令，中文會變亂碼。

SHELL := sh

# worktree 共用主目錄的 .env：以 git common dir 的上一層當主目錄
ROOT := $(patsubst %/.git,%,$(shell git rev-parse --path-format=absolute --git-common-dir))

-include $(ROOT)/.env
# 把 .env.example 列出的變數全部 export 給子程序（mvnw、vite、docker compose）
ENV_VARS := $(shell sed -n 's/^\([A-Z_][A-Z0-9_]*\)=.*/\1/p' .env.example 2>/dev/null)
export $(ENV_VARS)
export PYTHONUTF8 := 1
# optional (commented in .env.example, so not in ENV_VARS): pass it on to demo-seed / demo-verify without echoing it
export CDP_DEMO_PASSWORD
# 測試不受 .env 與外部環境影響：移除上列變數，改由 application-test.yml 提供
# （Spring 環境變數優先於 profile yml，例如 ENGINE_POLL_INTERVAL 會覆蓋 cdp.engine.poll-interval）
NO_DOTENV := env $(foreach v,$(ENV_VARS),-u $(v))

# 服務目錄；尚未建立時對應步驟自動略過
API_DIR := api
WEB_DIR := web
MVNW := ./mvnw -B
COMPOSE := docker compose --env-file "$(ROOT)/.env" -f docker-compose.yml

<<<<<<< HEAD
.PHONY: help setup ci-setup check lint test api-run web-dev infra-up infra-down demo-seed demo-verify
=======
.PHONY: help setup ci-setup check lint test api-run web-dev infra-up infra-down demo-seed perf
>>>>>>> develop

help:
	@echo "make setup      install deps (Maven repo in ~/.m2, node_modules at checkout root)"
	@echo "make ci-setup   install deps in CI (same steps as setup)"
	@echo "make check      lint + tests (container tests skipped when Docker is unreachable)"
	@echo "backend tests incl. containers, from main checkout as one command:"
	@echo "  docker compose --env-file .env -f .worktrees/<task_id>/compose.yaml run --rm test"
	@echo "make infra-up   start local PostgreSQL + RabbitMQ (docker compose)"
	@echo "make infra-down stop local services (data kept)"
	@echo "make api-run    run backend on API_PORT"
	@echo "make web-dev    run frontend dev server on WEB_PORT"
	@echo "make demo-seed  create demo journeys + customers on the running backend (needs make api-run)"

# 前端相依由根目錄 package.json 的 npm workspaces 安裝到 checkout 根目錄的 node_modules
setup:
	@if [ -f "$(API_DIR)/pom.xml" ]; then \
		cd "$(API_DIR)" && $(MVNW) -q -DskipTests test-compile; \
	else echo "skip api deps: no $(API_DIR)/pom.xml"; fi
	@if [ -f package-lock.json ]; then \
		npm ci; \
	else echo "skip web deps: no package-lock.json"; fi

# CI 與本機走同一套步驟，避免兩邊不一致
ci-setup: setup

check: lint test
	@echo "check passed"

lint:
	@if [ -f "$(API_DIR)/pom.xml" ]; then \
		cd "$(API_DIR)" && $(MVNW) -q spotless:check; \
	else echo "skip api lint: no $(API_DIR)/pom.xml"; fi
	@if [ -f "$(WEB_DIR)/package.json" ]; then \
		npm run lint -w "$(WEB_DIR)"; \
	else echo "skip web lint: no $(WEB_DIR)/package.json"; fi

# mvnw verify 跑 src/test/java 全部（含 com.cdp.acceptance）；vitest 跑 src/ 與 tests/（含 tests/acceptance）
# 連不到 Docker（本機 sandbox）或 CONTAINER_TESTS=skip 時用 -Punit-only 略過需要容器的測試並提示 compose 指令；
# CI（CI 變數非空）一律跑全部，Docker 有問題就直接失敗。見 docs/design/INIT-01.md「驗證方式」
test:
	@if [ -f "$(API_DIR)/pom.xml" ]; then \
		if [ -n "$$CI" ] || { [ "$(CONTAINER_TESTS)" != skip ] && timeout 15 docker info >/dev/null 2>&1; }; then \
			cd "$(API_DIR)" && $(NO_DOTENV) $(MVNW) verify; \
		else \
			echo "Docker not reachable or CONTAINER_TESTS=skip: skipping container tests (acceptance/, *IntegrationTest)."; \
			echo "Run all backend tests from the main checkout as a single command:"; \
			echo "  docker compose --env-file .env -f .worktrees/<task_id>/compose.yaml run --rm test"; \
			cd "$(API_DIR)" && $(NO_DOTENV) $(MVNW) -Punit-only verify; \
		fi; \
	else echo "skip api tests: no $(API_DIR)/pom.xml"; fi
	@if [ -f "$(WEB_DIR)/package.json" ]; then \
		$(NO_DOTENV) npm test -w "$(WEB_DIR)"; \
	else echo "skip web tests: no $(WEB_DIR)/package.json"; fi

api-run:
	cd "$(API_DIR)" && $(MVNW) spring-boot:run

web-dev:
	npm run dev -w "$(WEB_DIR)"

infra-up:
	$(COMPOSE) up -d --wait

infra-down:
	$(COMPOSE) down

# demo data via public API only; backend must be running (make api-run)
demo-seed:
	python3 tools/demo-seed/seed.py

<<<<<<< HEAD
# API-level end-to-end check after demo-seed; needs make api-run and the same CDP_DEMO_PASSWORD
demo-verify:
	python3 tools/demo-seed/verify.py
=======
# relay / loop load test (D-038); needs make infra-up and a running backend (make api-run). Not part of make check or CI.
# Optional: make perf PERF_SIZES=1000,10000 PERF_TIMEOUT=900
perf:
	python3 tools/perf/run.py
>>>>>>> develop
