# SIM Monitor: build, test, release. `make help` lists targets.
# Dev tooling comes from .devenv via env.sh; the release server runs in Docker.
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

# Local settings (SIM_MONITOR_HOST, ...): copy .env.example to .env.
-include .env
export

VERSION   := $(shell cat VERSION)
ENV       := source ./env.sh &&
IMAGE     := sim-monitor
CONTAINER := sim-monitor
VOLUME    := sim-monitor-data
APK       := dist/sim-monitor-$(VERSION).apk

.PHONY: help
help: ## List targets
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

# --- tests ----------------------------------------------------------------------------------
.PHONY: test test-server test-web test-android
test: test-server test-web test-android ## Run every test suite and linter

test-server:
	$(ENV) cd server && uv run pytest -q && uv run ruff check . && uv run ruff format --check .

test-web:
	$(ENV) cd web && npm test && npm run lint && npm run build

test-android:
	$(ENV) cd android && ./gradlew -q testDebugUnitTest lintDebug

# --- development ----------------------------------------------------------------------------
.PHONY: dev gen-api
dev: need-env ## Run the dev server from source (data in server/data)
	$(ENV) cd server && uv run python -m app

gen-api: ## Regenerate dashboard API types (needs `make dev` running)
	$(ENV) cd web && npm run gen:api

# --- release server (Docker) ----------------------------------------------------------------
.PHONY: need-env image deploy seed-data backup logs status stop
need-env:
	@test -n "$${SIM_MONITOR_HOST:-}" || { echo "Set SIM_MONITOR_HOST in .env (cp .env.example .env)"; exit 1; }

image: ## Build the release image sim-monitor:$(VERSION)
	docker build --build-arg VERSION=$(VERSION) -t $(IMAGE):$(VERSION) -t $(IMAGE):latest .

deploy: need-env image ## (Re)start the release container; restarts with Docker at boot
	-docker rm -f $(CONTAINER) >/dev/null 2>&1
	docker run -d --name $(CONTAINER) --network host --restart unless-stopped \
		--env-file .env -v $(VOLUME):/data $(IMAGE):$(VERSION)
	@echo "Dashboard: http://localhost:8000  (docker logs -f $(CONTAINER))"

seed-data: ## One-time: copy server/data (token, CA, DB) into the release volume
	@if docker run --rm -v $(VOLUME):/data alpine:3 test -e /data/token; then \
		echo "$(VOLUME) already has data; not overwriting"; exit 1; fi
	docker run --rm -v $(VOLUME):/data -v "$(CURDIR)/server/data":/src:ro alpine:3 \
		sh -c 'cp -a /src/. /data/ && chown -R 10001:10001 /data'

backup: ## Archive the release volume to backups/ (token, CA key, DB)
	mkdir -p backups
	docker run --rm -v $(VOLUME):/data:ro -v "$(CURDIR)/backups":/out alpine:3 \
		sh -c 'tar czf /out/$(VOLUME)-$$(date +%Y%m%d-%H%M%S).tgz -C /data . && chown $(shell id -u):$(shell id -g) /out/*.tgz'
	@ls -1t backups | head -1

logs: ## Follow the release server's logs
	docker logs -f $(CONTAINER)

status: ## Container and health status
	@docker ps -a --filter name=^$(CONTAINER)$$ --format '{{.Names}}  {{.Image}}  {{.Status}}'

stop: ## Stop the release container (stays stopped across reboots)
	docker stop $(CONTAINER)

# --- Android release ------------------------------------------------------------------------
.PHONY: apk
apk: ## Build the signed, shrunk release APK into dist/
	$(ENV) cd android && ./gradlew -q assembleRelease
	mkdir -p dist
	cp android/app/build/outputs/apk/release/app-release.apk $(APK)
	@ls -l $(APK)
