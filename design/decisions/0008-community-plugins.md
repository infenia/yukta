# ADR 0008: Community plugins outside the repository

- **Status:** accepted on 2026-11-13. Built: the `plugins/external` module with command, image and MCP (stdio and HTTP) forms and digest pinning, and the removal of `KV_STORE` and `SESSION_MEMORY` (no references remain in `core` or `plugins`). Tier 3 (streaming protocol) is not planned.
- **Date:** 2026-11-13 (updated 2026-11-14; condensed from the community-plugins plan on 2026-11-22)
- **Decided by:** Arun, in a design conversation.
- **Epic:** the Yukta Roadmap epic for community plugins. Code: `plugins/external`, schemas in `plugin-api/src/main/resources/schema/plugin/v1/`.

## Context

A plugin is a Java `ProcessorPlugin.process(Flux<Message<?>>, Map)` and a Spring bean, discovered at compile time (`optionalPlugins` in `boot/build.gradle.kts`). The message has no wire schema, metadata needs running code, and there is no SPI version. Someone outside the project cannot add a plugin without rebuilding Yukta, and only in Java. The engine already owns retries, timeouts, checkpoints and tracing around the plugin call, so a plugin is only a function over JSON-like messages.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Mechanism | **Out of process over a wire contract.** Rejected: runtime JAR loading (Java only, no native image) and WASM (host functions, uneven toolchains). |
| D2 | Shape | A plugin is a directory with a manifest (`apiVersion: yukta/plugin/v1`). At start-up a generic adapter (`ExternalProcessorPlugin`, `ExternalTerminalPlugin`) is registered per manifest; the engine is unchanged. The manifest is data: ports and timeout are static. |
| D3 | Message contract | One process per message. stdin: one JSON object `{apiVersion: yukta/message/v1, config, message}`; stdout: `{payload, port?, metadata?}` (or one per line); stderr: live logs; non-zero exit fails the node. Published as JSON Schema; a plugin cannot set engine fields. |
| D4 | Run forms | `run.command` (subprocess, inside the trust boundary), `run.image` (container per message through the container CLI, operator picks docker/podman/nerdctl), `run.mcp` over stdio or HTTP. |
| D5 | SDK | None in the first version. Maintain the schemas and one conformance command, `yukta-cli plugin validate`. Thin helpers can be generated later. |
| D6 | Loading | One directory, read at start-up. `PluginRegistry` stays immutable; a change needs a restart. No registry, hot reload or install API. Operator-only install with a default-deny enable list by type. |
| D7 | Trust | **No sandbox.** The operator who installs a plugin trusts it; isolation is `run.image` or MCP over HTTP. Kept: no shell, clean environment (only `PATH` and declared `SECRET` fields on stdin), timeout, output cap, concurrency limit, schema-validated output, no shadowing of built-in types. Digest pinning is optional and lives in the enable list (`TYPE@sha256:...`). |
| D8 | Docker | Container CLI, not the Docker API; no mounts, not privileged, no environment by default; pulled at start-up only; containers named and removed explicitly. Mounting the Docker socket into the Yukta container is documented, never the default. |
| D9 | MCP | stdio uses a small hand-written JSON-RPC client with a clean environment (the SDK passes the whole environment). HTTP reuses `McpClientGateway`, so `allowed-hosts` applies. The tool name and a hash of its input schema are pinned. |
| D10 | SPI version | `PluginApi.VERSION` (`MAJOR.MINOR`); `PluginRegistry` refuses another major or a newer minor. |
| D11 | Stateful built-ins | No plugin keeps data that outlives a run, and none stores data in core's PostgreSQL. **`KV_STORE` and `SESSION_MEMORY` are removed** with their stores, tables (migration drops `kv_entry`, `session_history`) and examples 06, 07, 09, 13; no deprecation release. `ANOMALY` and `DEDUPE` stay as bounded in-memory caches. |
| D12 | Triggers and streaming | Not in v1. They need a long-lived stream protocol (tier 3) reusing the `NodeFrame` frames, which would move from AGPL `plus/workers` to the Apache side. Until then use `WEBHOOK_TRIGGER` or `API_TRIGGER`. |

Security fix shipped first (#2327): `ProcessExecutorGateway` no longer passes Yukta's environment to subprocesses; `yukta.process.pass-env` lists what to pass.

## Consequences

- Removing `KV_STORE` and `SESSION_MEMORY` is breaking; the commit carries a `BREAKING CHANGE` note.
- A malicious `run.command` plugin owns the server; accepted and documented.
- A container or process start per message; the MCP form is for latency-sensitive nodes.
- Message envelope v1 is a public contract; keep it small.
- Open: whether terminals need more than "run once per message"; an MCP client in the CLI so `plugin validate` can check MCP plugins without a server.
