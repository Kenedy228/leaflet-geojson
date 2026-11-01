.DEFAULT_GOAL := help

# For example: make build MAVEN_ARGS=-Dmaven.repo.local=/tmp/leaflet-m2
MAVEN_ARGS ?=

.PHONY: help run check build test-frontend test verify

help:
	@printf '%s\n' \
	  'make run     Start frontend and API at http://localhost:8080 (requires PostGIS)' \
	  'make check   Check frontend JavaScript syntax and Git whitespace' \
	  'make build   Build the application JAR without running tests' \
	  'make test-frontend  Run frontend point identity regression tests' \
	  'make test    Run frontend and backend tests (requires a prepared test database)' \
	  'make verify  Run checks, tests and package the application (requires test database)'

run:
	cd backend && ./mvnw $(MAVEN_ARGS) spring-boot:run

check:
	@set -eu; for file in frontend/*.js; do node --check "$$file"; done
	git diff --check

build:
	cd backend && ./mvnw $(MAVEN_ARGS) -DskipTests package

test-frontend:
	node --test frontend/tests/*.test.cjs

test: test-frontend
	cd backend && ./mvnw $(MAVEN_ARGS) test

verify: check test-frontend
	cd backend && ./mvnw $(MAVEN_ARGS) verify
