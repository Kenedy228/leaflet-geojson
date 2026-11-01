.DEFAULT_GOAL := help

# For example: make build MAVEN_ARGS=-Dmaven.repo.local=/tmp/leaflet-m2
MAVEN_ARGS ?=
export MAVEN_ARGS BACKUP_FILE CONFIRM_RESTORE

.PHONY: help init-env init-editor dev run db-up db-down db-seed db-reset-demo db-backup db-restore check build test-frontend test-infra install-browser test-browser test verify lint format-check format ci dev-frontend

help:
	@printf '%s\n' \
	  'make init-env  Create private .env without replacing an existing file' \
	  'make init-editor  Enable local editor with private generated credentials' \
	  'make dev     Start local PostGIS and frontend/API with demo data' \
	  'make dev-frontend  Run Vite HMR and API proxy for frontend development' \
	  'make run     Start frontend/API using .env (without demo seeding)' \
	  'make db-up / db-down  Start / stop local PostGIS, preserving its volume' \
	  'make db-seed / db-reset-demo  Restore only the licensed demo reference rows' \
	  'make db-backup  Save local DB to a private backups/*.dump file' \
	  'make db-restore BACKUP_FILE=... CONFIRM_RESTORE=YES  Restore a trusted backup' \
	  'make check   Check Svelte/TypeScript, Python syntax and Git whitespace' \
	  'make lint / format-check  Check frontend lint and frontend/Java formatting' \
	  'make format  Apply frontend and Java formatting' \
	  'make ci      Run all mandatory checks including Chromium' \
	  'make build   Build the application JAR without running tests' \
	  'make test-frontend  Run frontend Vitest regressions' \
	  'make install-browser  Install pinned browser test dependencies and Chromium' \
	  'make test-browser  Run popup and batch-save regressions on disposable PostGIS and app' \
	  'make test    Run tests using a disposable, isolated PostGIS' \
	  'make verify  Run checks, tests and build the application JAR'

init-env:
	python3 infra/manage.py init-env

init-editor:
	python3 infra/manage.py init-editor

dev:
	python3 infra/manage.py dev

run:
	python3 infra/manage.py run

db-up:
	python3 infra/manage.py up

db-down:
	python3 infra/manage.py down

db-seed:
	python3 infra/manage.py seed

db-reset-demo:
	python3 infra/manage.py reset-demo

db-backup:
	python3 infra/manage.py backup

db-restore:
	python3 infra/manage.py restore

check:
	npm --prefix frontend run check
	python3 -m py_compile infra/manage.py infra/browser_test.py
	git diff --check

build:
	cd backend && ./mvnw $(MAVEN_ARGS) -DskipTests package

test-frontend:
	npm --prefix frontend test

test-infra:
	python3 -m unittest discover -s infra/__tests__ -v

install-browser:
	npm --prefix frontend ci --ignore-scripts
	npm --prefix frontend exec -- playwright install chromium

test-browser:
	python3 infra/browser_test.py

test: test-frontend test-infra
	python3 infra/manage.py test

verify: check test-frontend test-infra
	python3 infra/manage.py verify

lint:
	npm --prefix frontend run lint

format-check:
	npm --prefix frontend run format:check
	cd backend && ./mvnw $(MAVEN_ARGS) spotless:check

format:
	npm --prefix frontend run format
	cd backend && ./mvnw $(MAVEN_ARGS) spotless:apply

# Keep the full CI gate sequential even under make -j.
ci:
	$(MAKE) lint format-check
	$(MAKE) verify
	$(MAKE) test-browser

dev-frontend:
	npm --prefix frontend run dev
