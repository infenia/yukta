# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

.PHONY: help build build-all test cover cover-check fmt fmt-check mod-tidy mod-verify vet lint sec vuln check clean

# Variables
BINARY_NAME := yukta
BUILD_DIR := build
CMD_DIR := cmd/yukta
GOOS ?= $(shell go env GOOS)
GOARCH ?= $(shell go env GOARCH)

# Pinned tool versions (run via `go run`, so builds are reproducible and need no installs).
GOLANGCI_LINT := github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.14.0
GOSEC := github.com/securego/gosec/v2/cmd/gosec@v2.29.0
GOVULNCHECK := golang.org/x/vuln/cmd/govulncheck@v1.8.0

# Minimum per-function coverage (%), with per-file overrides as path=min.
COVER_MIN := 100
COVER_OVERRIDES := cmd/yukta/main.go=0 internal/client/mock.go=0 test/integration_test.go=0

# Build targets for build-all
TARGETS := \
	linux/amd64 \
	linux/arm64 \
	darwin/amd64 \
	darwin/arm64 \
	windows/amd64

help: ## Display this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

build: ## Build CLI for GOOS/GOARCH (default: current OS/arch)
	@mkdir -p $(BUILD_DIR)
	@out=$(BUILD_DIR)/$(BINARY_NAME)-$(GOOS)-$(GOARCH)$(if $(filter windows,$(GOOS)),.exe); \
		echo "Building $$out"; \
		CGO_ENABLED=0 GOOS=$(GOOS) GOARCH=$(GOARCH) go build -ldflags='-s -w' -o $$out ./$(CMD_DIR)

build-all: ## Build CLI for all platforms (Linux/macOS/Windows)
	@for target in $(TARGETS); do \
		$(MAKE) --no-print-directory build GOOS=$${target%/*} GOARCH=$${target#*/} || exit 1; \
	done

test: ## Run Go tests
	@go test ./...

cover: ## Run tests with a fresh coverage profile
	@go test -coverprofile=coverage.out ./...

cover-check: cover ## Fail if any function is below its coverage minimum
	@go tool cover -func=coverage.out | awk -v min=$(COVER_MIN) -v ov="$(COVER_OVERRIDES)" ' \
		BEGIN { n = split(ov, o, " "); for (i = 1; i <= n; i++) { split(o[i], kv, "="); over[kv[1]] = kv[2] } } \
		$$1 == "total:" { next } \
		{ f = $$1; sub(/^[^\/]*\/[^\/]*\//, "", f); sub(/:[0-9]+:$$/, "", f); \
		  pct = $$NF; sub(/%/, "", pct); need = (f in over) ? over[f] : min; \
		  if (pct + 0 < need) { print "FAIL " $$1 " " $$2 ": " pct "% (need " need "%)"; bad = 1 } } \
		END { if (bad) exit 1; print "Coverage OK" }'

fmt: ## Format code with gofmt
	@gofmt -w .

fmt-check: ## Fail if any file is not gofmt-formatted
	@out=$$(gofmt -l .); if [ -n "$$out" ]; then echo "Unformatted files:"; echo "$$out"; echo "Run 'make fmt'."; exit 1; fi

mod-tidy: ## Tidy go.mod/go.sum
	@go mod tidy

mod-verify: ## Verify module checksums
	@go mod verify

vet: ## Run go vet
	@go vet ./...

lint: ## Run golangci-lint (pinned)
	@go run $(GOLANGCI_LINT) run ./...

sec: ## Run gosec (pinned)
	@go run $(GOSEC) -quiet ./...

vuln: ## Run govulncheck (pinned)
	@go run $(GOVULNCHECK) ./...

check: fmt-check mod-verify vet lint sec vuln cover-check ## Everything :cli:check runs

clean: ## Remove build artifacts and temporary files
	@rm -rf $(BUILD_DIR) coverage.out
