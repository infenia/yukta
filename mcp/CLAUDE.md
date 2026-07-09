# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# MCP Module CLAUDE.md

## Overview
MCP server implementation and tool providers.

## Build & Development Commands
```bash
# Run MCP tests
./gradlew :mcp:test
```

## Features
- Integrates with AI agents using Model Context Protocol.
- Provides native MCP integration for Yukta.

## Key Files
- `src/main/java/com/infenia/yukta/mcp/AppMcpTools.java`: `@McpTool` providers (workflow/session/plugin management).
- `src/main/java/com/infenia/yukta/mcp/AppMcpPrompts.java`: `@McpPrompt` templates.
- `src/main/java/com/infenia/yukta/mcp/AppMcpResources.java`: `@McpResource` providers.
- `src/main/java/com/infenia/yukta/mcp/provider/`: Backing implementations (e.g. `DefaultLogProvider`, `DefaultSessionInfoProvider`, `DefaultPluginInfoProvider`).

## Tech Stack
- **Spring AI MCP Server** (WebFlux)

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
