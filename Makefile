# 開發指令入口。技術棧與目錄慣例見 docs/design/INIT-01.md。
# 需在 Git Bash（Windows）與 Linux（CI）都能執行；GNU Make 3.81 相容。
# recipe 內只用 ASCII：Windows 版 make 會以系統 codepage 轉碼指令，中文會變亂碼。

SHELL := sh

# worktree 共用主目錄的 .venv／node_modules／.env：以 git common dir 的上一層當主目錄
ROOT := $(patsubst %/.git,%,$(shell git rev-parse --path-format=absolute --git-common-dir))

-include $(ROOT)/.env

PYTHON ?= python
export PYTHONUTF8 := 1

VENV := $(ROOT)/.venv
ifeq ($(OS),Windows_NT)
VENV_BIN := $(VENV)/Scripts
else
VENV_BIN := $(VENV)/bin
endif
VPY := $(VENV_BIN)/python

# 服務目錄（見設計文件「目錄結構」）；尚未建立時對應步驟自動略過
API_DIR := api
WEB_DIR := web

.PHONY: help setup ci-setup check lint test

help:
	@echo "make setup     create local env (.venv, node_modules in main repo dir)"
	@echo "make ci-setup  install deps in CI (same steps as setup)"
	@echo "make check     lint + tests (incl. */tests/acceptance)"

setup:
	@if [ ! -x "$(VPY)" ] && [ ! -x "$(VPY).exe" ]; then \
		echo "create $(VENV)"; $(PYTHON) -m venv "$(VENV)"; \
	fi
	@if [ -f "$(API_DIR)/requirements.txt" ]; then \
		"$(VPY)" -m pip install -q -r "$(API_DIR)/requirements.txt"; \
	else echo "skip python deps: no $(API_DIR)/requirements.txt"; fi
	@if [ -f "$(WEB_DIR)/package.json" ]; then \
		cd "$(WEB_DIR)" && npm install; \
	else echo "skip node deps: no $(WEB_DIR)/package.json"; fi

# CI 與本機走同一套步驟，避免兩邊不一致
ci-setup: setup

check: lint test
	@echo "check passed"

lint:
	@if [ -d "$(API_DIR)" ]; then \
		"$(VPY)" -m ruff check "$(API_DIR)"; \
	else echo "skip python lint: no $(API_DIR)/"; fi
	@if [ -f "$(WEB_DIR)/package.json" ]; then \
		cd "$(WEB_DIR)" && npm run lint; \
	else echo "skip web lint: no $(WEB_DIR)/package.json"; fi

test:
	@if [ -d "$(API_DIR)" ]; then \
		"$(VPY)" -m pytest "$(API_DIR)"; \
	else echo "skip python tests: no $(API_DIR)/"; fi
	@if [ -f "$(WEB_DIR)/package.json" ]; then \
		cd "$(WEB_DIR)" && npm test; \
	else echo "skip web tests: no $(WEB_DIR)/package.json"; fi
