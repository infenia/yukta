# Community plugins design

Design for plugins that live outside the Yukta repository and can be written in any language: what couples plugins to Java
today, the options considered, the chosen design, the trust model, what happens to stateful built-ins, and the phases.

Status: **design only, nothing built.** Decisions below were taken in a design conversation on 2026-11-13. Items marked
*(to verify)* were read from the code but not tested.

## Goal and non-goals

Goal: someone outside the project can add a processor or terminal to a Yukta server without changing or rebuilding Yukta,
in any language, by following a published manifest and message contract. No SDK is required.

Non-goals (first version):

- External **triggers** and true streaming nodes. They need a long-lived stream protocol (tier 3 below).
- Loading JARs at runtime. It is Java only and does not work in the native image.
- A sandbox, a plugin registry or marketplace, hot reload, install through the API.
- Moving the generic built-in plugins out of process.

## Today (what couples plugins to Java and the repository)

| Coupling | Where | Effect |
|---|---|---|
| The contract is Java types | `ProcessorPlugin.process(Flux<Message<?>>, Map)` | No language-neutral definition of a plugin |
| No message wire schema | `Message` is a Java interface with about 25 fields; the only wire form is Jackson's conversion of `DefaultMessage` in `plus/workers/NodeRunner` | Accidental, unversioned format |
| Compile-time discovery | `boot/build.gradle.kts` hard-codes `optionalPlugins`; `PluginRegistry` takes a fixed `List<Plugin>` of Spring beans | Even a Java plugin needs a Yukta rebuild |
| Spring in every plugin | all 33 built-ins are `@Component`s | The real contract is "be a Spring bean" |
| Hidden services | heartbeat, log sink, signals and sub-workflow depth travel in Reactor context keys | Part of the contract is not in the interface |
| Behaviour as methods | `getOutputPorts(config)`, `getDefaultTimeout(config)`, `validateConfig` | Metadata needs running code |
| No SPI version | risk `noVersion` in `design/assessments/plugin-api.md` | No compatibility promise |

What already helps:

- The engine owns retries, timeouts, checkpoints, pause/skip and tracing around the plugin call, so a plugin is only a
  function over messages. Payloads are JSON maps in practice.
- `process-executor`, `http-request` and `mcp-tool-call` already reach code in other languages; they are just not
  presented as first-class plugin types.
- `RemoteNodeExecutor` (core) and `NodeFrame` (`plus/workers`, AGPL) are most of a streaming protocol, kept for tier 3.

## Options considered

| Option | Verdict |
|---|---|
| Runtime JAR loading (PF4J, `ServiceLoader`) | Rejected: Java only, incompatible with the native image |
| WASM (Chicory, Extism) | Not now: network and file access need host functions, language toolchains are uneven |
| Out-of-process over a wire contract | **Chosen.** The model of Terraform providers, Airbyte connectors, LSP and MCP |

SDK position: SDK cost follows protocol complexity. One JSON object in and one out needs no SDK, so none is shipped in the
first version. What the project maintains is a JSON Schema for the manifest and the message envelope, and one conformance
command (`plugin validate` in the Go `cli`). Thin helper libraries (Python, TypeScript) are optional and can be generated
from the schema later.

## Design

A community plugin is a directory with a manifest. At startup Yukta reads each enabled manifest and registers one generic
adapter (`ExternalProcessorPlugin` or `ExternalTerminalPlugin`) that implements the existing interfaces. The engine does
not change, so retries, timeouts, `onError`, checkpoints and tracing apply as for any node, and the type appears in
`list_plugins` and the UI.

Tiers:

1. **Manifest plus a command or image** (built first).
2. **Manifest plus an MCP tool** (built second).
3. **Streaming protocol** for triggers and streaming nodes (not planned; see "Later").

### Manifest

```yaml
apiVersion: yukta/plugin/v1
type: SLACK_NOTIFY
category: processor            # processor | terminal
description: Post a message to Slack
config:                        # same shape as ConfigField
  - { name: channel, type: STRING, required: true }
  - { name: token,   type: SECRET, required: true }
ports: [default, failed]
timeout: 30s
run:                           # exactly one of the three
  command: [python3, main.py]
  # image: ghcr.io/acme/slack-notify@sha256:9f2c...
  # mcp: { command: [npx, slack-mcp], tool: post_message }   # or mcp: { url: ..., tool: ... }
```

The manifest is data: ports and timeout are static, not functions of the config. An external type may not reuse a
built-in type name; `PluginRegistry` already fails startup on a duplicate.

### Message contract (command and image forms)

One process per message.

- **stdin:** one JSON object `{ "apiVersion": "yukta/message/v1", "config": {...}, "message": { "id", "traceId",
  "metadata", "payload" } }`. `SECRET` fields, `decrypted:` values and `${...}` placeholders are
  resolved for the message by the adapter (`VariableResolver`, as built-in plugins do; the engine does not resolve
  config), and only the fields the manifest declares are sent. A plugin ignores properties it does not
  know and refuses an `apiVersion` it does not know.
- **stdout:** `{ "payload": ..., "port"?: "...", "metadata"?: {...} }`, or one such object per line for several outputs.
- **stderr:** live log lines, through the existing `PluginLogSink`.
- **Exit code:** non-zero fails the node; the engine's retry and `onError` policies decide what follows.

This is **message envelope v1**, published as JSON Schema. It is a trimmed view of `Message`: retry, dead-letter,
sequence and history fields stay inside the engine. A plugin may set the payload, a declared port and metadata entries;
it cannot set engine fields such as trace or workflow id. The map is called `metadata`, as in `Message.metadata()`.
Schemas: `plugin-api/src/main/resources/schema/plugin/v1/` (`manifest`, `message-in`, `message-out`).

### Run forms

| Form | Runs as | Best for |
|---|---|---|
| `run.command` | Subprocess on the host | Scripts and binaries the operator trusts |
| `run.image` | Container per message (`docker run --rm -i`) | Any language, bundled dependencies, isolation |
| `run.mcp` over stdio (a command or an image) | Long-lived subprocess or container | Latency-sensitive nodes, existing MCP servers |
| `run.mcp` over HTTP | Separate service | Yukta in Docker or Kubernetes, strongest separation |

`run.image`:

- Yukta calls the container CLI, not the Docker API: no new dependency, works in the native image, and the operator picks
  `docker`, `podman` or `nerdctl` in configuration.
- Defaults: no volume mounts, not privileged, no environment passed in, network on. The operator may add mounts, resource
  limits or `--network none` per plugin.
- At startup the image is inspected and pulled only when missing, never per message; a failed pull fails startup.
- Operator arguments per plugin: `yukta.plugins.container.args.<TYPE>`, beside `yukta.plugins.container.runtime`. The CLI
  process gets `PATH` plus its own connection variables (`HOME`, `DOCKER_HOST`, `XDG_RUNTIME_DIR`, ...), none of which
  reach the container.
- Each container is named and removed explicitly on timeout or cancel, because killing the `docker run` client can leave
  the container running.
- Cost: a container start per message. Use the MCP form when latency matters.

`run.mcp`:

- A tool call is a request/response processor, and the MCP SDKs are maintained by others.
- `mcp-tool-call` reaches servers over HTTP only today; **stdio transport is the main new work** of tier 2.
- The config form can be derived from the tool's input schema (`MCP_LIST_TOOLS` already reads it), so the manifest stays
  a few lines.
- The tool name and a hash of its schema are pinned, so a server cannot change a tool after it was accepted. Tool results
  are untrusted text when they feed an LLM node.

### Loading and distribution

- Plugins are read from one directory at startup. `PluginRegistry` stays immutable; a plugin change needs a restart.
- A plugin is a git repository (or an image reference) placed in that directory by the operator. No registry.
- `plugin validate <dir>` in the `cli` checks the manifest against the schema and runs the plugin once with a sample
  message. It is the conformance check for authors and operators.

### Deployments

| Deployment | Subprocess plugins | Image plugins | MCP over HTTP |
|---|---|---|---|
| Linux binary under systemd, NixOS | yes | yes, if a container runtime is installed | yes |
| Stock Docker image (distroless, no shell, Python or Node) | only static binaries, or a custom image | needs the Docker socket mounted: root-equivalent on the host, not the default | **recommended** |
| Kubernetes | as above | no | **recommended** (sidecar or separate pod) |

## Trust model and security

"External" has two meanings, and only one takes the plugin outside Yukta's trust boundary:

| How it runs | Boundary |
|---|---|
| MCP over HTTP on another container or host | Outside. It sees only what Yukta sends; Yukta treats replies as untrusted. |
| `run.image` with default flags | Isolated by the container: no Yukta files, environment or processes. |
| `run.command` | **Inside.** It runs as Yukta's OS user and can read whatever Yukta can. |

Decision: **no sandbox is built.** The rule is the one Terraform providers, Jenkins plugins and Airflow providers use: the
operator who installs a plugin trusts it. Operators who want isolation use `run.image` or MCP over HTTP. The docs state
this plainly.

Kept, because they are cheap and guard against buggy plugins and against workflow authors (a different party from the
operator):

- **Operator-only install and an enable list by type** (default deny). A workflow can never define a plugin; otherwise
  editing a workflow would mean running code on the server.
- **No shell.** The command is an argument array; `useShell` is not available to external plugins.
- **Clean environment.** The process starts with `PATH` and nothing else; only declared `SECRET` fields are resolved, and
  they arrive on stdin, never in arguments or the environment. Logs are redacted as today.
- **Limits.** Timeout, a finite default output cap, a concurrency limit, and the existing process-group kill.
- **Untrusted output.** Validated against the envelope schema and size-capped.
- **No shadowing** of built-in types; the plugin type (and digest, when pinned) is written to the execution log.

Relaxed:

- **Digest pinning** of a plugin's files is optional and recommended, not mandatory (an image digest does the same job
  for `run.image`). The pin lives in the operator's enable list (`TYPE@sha256:<digest of the plugin directory>`), not in
  the manifest, which it would have to protect; a mismatch fails startup.
- A plugins directory writable by the Yukta service user is a startup warning, not a refusal.
- No network or filesystem permission declarations in the manifest: nothing would enforce them.

**Finding fixed first (confirmed by a test):** `ProcessExecutorGateway` added the configured variables to the subprocess
environment without clearing it, so a subprocess inherited Yukta's whole environment, which is also where
`EnvSecretProvider` reads secrets from. Fixed in #2327: a process starts with `PATH` and its node's `env`;
`yukta.process.pass-env` lists server variables to pass (`*` for all, a trusted local server only).

## Stateful plugins and the shared database

Rules:

1. A plugin in the default set keeps no data that outlives a run or is shared between runs.
2. No plugin stores its data in the PostgreSQL database that core uses.

| Kind | Plugins | Decision |
|---|---|---|
| Persistent user data | `KV_STORE` (`R2dbcKeyValueStore`), `SESSION_MEMORY` (`R2dbcSessionHistoryStore`) | **Removed** in phase 5 with every store (memory, file, PostgreSQL) and examples 06, 07, 09 and 13; migration `V2` drops `kv_entry` and `session_history`. Replacements are community plugins with their own store, outside this repository. **Breaking** for workflows that use them. |
| Shared in-memory state | `ANOMALY`, `DEDUPE` (`DecisionWindow`, a static map) | Reviewed in phase 5: bounded (LRU of 1,000 windows, each capped at the node's `max` items), memory only, lost on restart. Only the opt-in `persist: session` shares a window between runs of a session. Kept as a bounded cache; revisit if it must survive a restart. |
| Engine run state | `WAIT_FOR_SIGNAL`, `ASK_INPUT` | Allowed: engine features packaged as plugins |
| Disposable caches and limits | template, expression, model and MCP session caches, LLM rate limits | Allowed |

All other built-ins stay in-process Java. External plugins satisfy rule 2 by construction: they get no store interface
and no database credentials.

Decisions for phases 4 and 5 (2026-11-14):

- MCP over stdio uses a small hand-written JSON-RPC client (initialize, tools/list, tools/call), one long-lived process
  per plugin with a clean environment. The MCP Java SDK's stdio transport passes the whole server environment.
- MCP over HTTP reuses `McpClientGateway` (`external` may depend on `mcp-tool-call`), so `allowed-hosts` applies.
- The tool pin lives in the enable list: for an MCP plugin `TYPE@sha256:<digest>` covers the directory and the tool's
  input schema read at startup; a changed tool fails startup.
- Phase 5 removes `KV_STORE` and `SESSION_MEMORY` in this repository, their stores and the examples that use them
  (06, 07, 09, 13), and drops their tables in a migration; their replacements are built outside it.

## Phases (each its own reviewed PR)

1. **Prerequisites.** Clean subprocess environment in `process-executor` (security fix, ships on its own). Message
   envelope v1 and manifest as JSON Schema. An SPI version with a startup check. The SPI version is `PluginApi.VERSION` (`MAJOR.MINOR`);
   `Plugin.apiVersion()` defaults to it, a plugin built outside the repository overrides it, and `PluginRegistry` refuses
   another major or a newer minor. The manifest's `yukta/plugin/v1` is checked against the same major in phase 2.
2. **Command plugins.** Manifest loader, enable list, `ExternalProcessorPlugin` and `ExternalTerminalPlugin`, output
   validation and limits, `plugin validate` in the `cli`, one example plugin, author and operator docs.
3. **Image plugins.** `run.image`, runtime choice, pull at startup, named containers and cleanup.
4. **MCP plugins.** Stdio transport in `McpClientGateway`, the `run.mcp` form, config derived from the tool schema, tool
   schema pin.
5. **Stateful built-ins.** Removal of `KV_STORE` and `SESSION_MEMORY` (decided 2026-11-14: no deprecation release), their
   stores and tables, and the examples built on them; review of `DecisionWindow`.

Later, only when a concrete need appears:

- **Tier 3:** a streaming protocol for triggers and streaming nodes. It reuses the `NodeFrame` frames
  (`START / MESSAGE / HEARTBEAT / COMPLETE / ERROR`, plus `LOG` and a version handshake) and requires moving that protocol
  from AGPL `plus/workers` to the Apache side (agreed in principle; worker pools and scheduling stay in Plus). Until then
  an external program starts a workflow through `WEBHOOK_TRIGGER` or `API_TRIGGER`.
- A long-lived process per command plugin, a per-host egress proxy, an optional OS sandbox, hot reload, a registry, thin
  SDKs, moving stateless built-ins such as `SQL_QUERY` out of process.

## Risks

- **A malicious `run.command` plugin owns the server.** Accepted by the trust model; mitigated by default deny and by
  offering `run.image` and MCP over HTTP.
- **Cold start per message** for command and image plugins. Mitigated by the MCP form.
- **Mounting the Docker socket** into a Yukta container gives root on the host. Documented, never the default.
- **Removing `KV_STORE` and `SESSION_MEMORY`** breaks existing workflows, with no deprecation release (decided 2026-11-14). The commit carries a `BREAKING CHANGE` note for the changelog.
- **Envelope v1 becomes a public contract.** Keep it small; version it from the first release.

## Open points

- Where the adapter lives: resolved in phase 2, `plugins/external`, allowed to depend on `process-executor` and (phase 4)
  `mcp-tool-call` in the ArchUnit rules.
- Whether terminals need anything beyond "run once per message" (for example `completionMetadata`).
- `yukta-cli plugin validate` does not run an MCP plugin; the server logs its digest. Add an MCP client to the CLI if
  authors need the check without a server.
