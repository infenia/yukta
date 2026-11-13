# Unified AI backend and AGENT_SESSION (#1960, #1958)

Status: plan, nothing implemented. Phase 1 of #1960 (the design note). Depends on #1955/#1956 (merged or in #2292).

## Goal
One generic AI surface: any model or agent harness is configuration, not code. An AI agent (or person) must be able to learn
how to configure every option from the platform itself: `GET /api/plugins/{type}` / `get_plugin_details` (config schema with
descriptions, defaults, allowed values), plus a listing of backends and harness presets. No external docs needed.

## Verified starting point (origin/main)
- Control planes are gone (S7). `decision` is the only user of `SystemOneModel`/`ChatDecisionModel`; `DecisionModels` picks by `ModelSpec.Backend {SYSTEMONE, LLM}`.
- `LlmGateway.call(LlmRequest)` is synchronous and returns one `LlmResponse`; it owns provider choice, rate limits, deny patterns, budgets.
- `ProcessExecutorGateway.executeForResult(spec, PluginLogSink)` already hands every stdout/stderr line to the sink as the process writes it, and cancelling the subscription kills the process tree (corrected in phase 3; the first version of this note said the gateway had no streaming). `AGENT_SESSION` parses events in its own sink and needs no new gateway method.
- Claude Code (2.1.322, installed) supports `-p` with `--output-format stream-json`, `--resume <id>`, `--allowedTools`, `--permission-mode`. Codex is not installed here: its flags must be checked against the real CLI in phase 6, not assumed.
- #1955/#1956: new plugin types are tiered; `AGENT_SESSION` is EXTENDED + restricted, so it is off unless named in `yukta.plugins.enabled`.

## Decisions taken
- Presets are JSON (Jackson already everywhere), shipped as classpath resources; operators add files from a configured directory.
- Alpha: renames and breaking config changes are allowed; no compatibility aliases needed.
- Hardening (sandbox, secrets, redaction) stays out of this phase, seams are kept (see #1960 section 6).

## Design
### 1. Backend layer (`kind`-selected, in a shared module `llm-call` and `decision` both use)
```
interface AiBackend { Mono<AiReply> complete(AiRequest r); Set<String> kinds... }   // one per kind
kinds: openai | anthropic | ollama | systemone | agent
```
- `LlmGateway` becomes the `openai/anthropic/ollama` implementation (behaviour unchanged, tests untouched). `SystemOneModel` becomes the `systemone` kind. `DecisionModel` stays the SPI of the decision types; `DecisionModels` resolves `backend` through the registry, so `decision` imports no concrete backend (#1958 item 1-2).
- Registry exposes `GET /api/ai/backends` and an MCP tool: kind, option names, description, defaults, required. This is what an agent reads to configure a backend.
- `systemone` fails only when used: the platform starts and validates with no Ollama (#1958 item 4; test: context loads with it unset, a workflow using it gives a clear error).
- Config keys: `backend`, `provider`, `baseUrl`, `model`, `keepAlive`, `numCtx` kept as they are; a rename is allowed but not needed.

### 2. AGENT_SESSION processor (EXTENDED, restricted)
Config: `harness` (preset id), `prompt`, `session`, `model`, `env`, `workingDir`, `timeout`, `maxTurns`, `onBusy`, `failureMode`.
Ports `success`, `failure`, `incomplete`; payload `{status, reason, text, sessionId, turn}`.
- Spawns only through `ProcessExecutorGateway` (existing allow-list) with a sink that parses stdout lines into events as they arrive; cancel kills the process tree.
- Presets (JSON): `command` template, how the prompt reaches the process (arg or stdin), `outputFormat` (`stream-json` | `json` | `text`) with a small event mapping (JSON path to event type: text, tool, question, done, session id), `resume` (flag and where the token comes from), `capabilities` (`resume`, `stream`, `ask`). `validateConfig` fails early when a node asks for what the preset cannot do.
- Discoverability: the `harness` field's `options` are filled from the loaded presets, and `GET /api/ai/harnesses` returns each preset with its capabilities and example config.
- Sessions: in-memory store behind a small interface, one turn at a time per session key, `onBusy` WAIT or FAIL.
- Ask: presets with `ask` map the harness question to `InputGateway.ask`; the node waits.
- Test without the vendor: a fixture harness script that speaks the preset protocol.

## Phases (one PR each)
1. This note.
2. Backend layer extraction + `systemone` as a kind + `/api/ai/backends` + MCP tool; decision imports no concrete backend. Tests: decision, llm-call, boot start with no Ollama.
3. Done, tests only (#2296): pin live line delivery and tree kill on cancel in `ProcessExecutorGatewayTest`.
4. `AGENT_SESSION` v1 with Claude Code preset: single turn, events, cancel, timeout, ports. `/api/ai/harnesses`.
5. Sessions (resume, store, `onBusy`, `maxTurns`).
6. Ask through `InputGateway`.
7. Codex preset with no Java change (check real CLI flags). Only now is the adapter considered stable.
8. Docs page, example workflow, `plugins/CLAUDE.md`, open the hardening issue.

## Open questions
- Does `stream-json` of the installed Claude Code carry a resumable session id in a stable field? Verify with a real run in phase 4 and pin it in the preset test.
- Is `maxTurns` generic or per preset (Claude Code has `--max-turns`; Codex may not)? Default: per preset capability.
- Where operator presets live: proposed `yukta.ai.presets-dir`.
