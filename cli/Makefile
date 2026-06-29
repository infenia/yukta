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

.PHONY: help build build-all test lint fmt clean

# Variables
BINARY_NAME := yukta
BUILD_DIR := build
CMD_DIR := cmd/yukta
GOOS ?= $(shell go env GOOS)
GOARCH ?= $(shell go env GOARCH)

# Build targets for build-all
TARGETS := \
	linux/amd64 \
	linux/arm64 \
	darwin/amd64 \
	darwin/arm64 \
	windows/amd64

help: ## Display this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

build: ## Build CLI for current OS/arch
	@echo "Building $(BINARY_NAME) for $(GOOS)/$(GOARCH)..."
	@mkdir -p $(BUILD_DIR)
	@go build -o $(BUILD_DIR)/$(BINARY_NAME) ./$(CMD_DIR)
	@echo "Build complete: $(BUILD_DIR)/$(BINARY_NAME)"

build-all: clean ## Build CLI for all platforms (Linux/macOS/Windows)
	@echo "Building $(BINARY_NAME) for all platforms..."
	@mkdir -p $(BUILD_DIR)
	@for target in $(TARGETS); do \
		goos=$${target%/*}; \
		goarch=$${target#*/}; \
		output=$(BUILD_DIR)/$(BINARY_NAME)-$$goos-$$goarch; \
		if [ "$$goos" = "windows" ]; then \
			output=$${output}.exe; \
		fi; \
		echo "  Building for $$goos/$$goarch -> $$output"; \
		GOOS=$$goos GOARCH=$$goarch go build -o $$output ./$(CMD_DIR); \
	done
	@echo "All builds complete in $(BUILD_DIR)/"

test: ## Run Go tests
	@echo "Running tests..."
	@go test -v ./...

lint: ## Run Go linters (go vet, go fmt)
	@echo "Running go vet..."
	@go vet ./...
	@echo "Checking code formatting with go fmt..."
	@if [ -n "$$(go fmt ./...)" ]; then \
		echo "Code formatting issues found. Run 'make fmt' to fix."; \
		exit 1; \
	fi
	@echo "Linting complete."

fmt: ## Format code with go fmt
	@echo "Formatting code..."
	@go fmt ./...
	@echo "Formatting complete."

clean: ## Remove build artifacts and temporary files
	@echo "Cleaning build artifacts..."
	@rm -rf $(BUILD_DIR)
	@echo "Clean complete."
