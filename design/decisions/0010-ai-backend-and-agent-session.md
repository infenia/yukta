# ADR 0010: Unified AI backend and AGENT_SESSION

- **Status:** accepted on 2026-11-13. Phases 1 to 3 were planned first; check the epic for later phases. The agent-console work in [ADR 0004](0004-agent-console.md) builds on it and wins where they differ.
- **Date:** 2026-11-13 (condensed from the ai-backend plan on 2026-11-22)
- **Decided by:** Arun.
- **Issues:** #1960 (unified AI backend), #1958; depends on #1955 and #1956.

## Context

Any model or agent harness should be configuration, not code, and an AI agent (or a person) should learn how to configure every option from the platform itself, with no external docs. Starting point: `decision` was the only user of `SystemOneModel` and `ChatDecisionModel`; `LlmGateway` owns provider choice, rate limits, deny patterns and budgets; `ProcessExecutorGateway.executeForResult` already streams stdout and stderr lines to a sink and cancel kills the process tree; Claude Code supports `-p`, `--output-format stream-json`, `--resume`, `--allowedTools` and `--permission-mode`.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Backend layer | An `AiBackend` interface selected by `kind`: `openai`, `anthropic`, `ollama`, `systemone`, `agent`. `LlmGateway` becomes the openai/anthropic/ollama implementation (behaviour unchanged); `SystemOneModel` becomes the `systemone` kind. `decision` resolves its backend through a registry and imports no concrete backend. |
| D2 | Self-description | `GET /api/ai/backends` and an MCP tool list each kind with option names, descriptions, defaults and required flags. `GET /api/plugins/{type}` / `get_plugin_details` carry the config schema. |
| D3 | Lazy failure | `systemone` fails only when used: the platform starts and validates with no Ollama. |
| D4 | `AGENT_SESSION` | A processor, tier EXTENDED and restricted (off unless named in `yukta.plugins.enabled`). Config: `harness`, `prompt`, `session`, `model`, `env`, `workingDir`, `timeout`, `maxTurns`, `onBusy`, `failureMode`. Ports `success`, `failure`, `incomplete`; payload `{status, reason, text, sessionId, turn}`. |
| D5 | Process launch | Only through `ProcessExecutorGateway` (existing allow-list). The node parses events in its own sink; no new gateway method. |
| D6 | Harness presets | JSON, shipped as classpath resources; operators add files from a directory (proposed `yukta.ai.presets-dir`). A preset gives the command template, how the prompt arrives (arg or stdin), the output format (`stream-json`, `json`, `text`) with an event mapping, how to resume, and capabilities (`resume`, `stream`, `ask`). `validateConfig` fails early when a node asks for what the preset cannot do. `GET /api/ai/harnesses` lists them with example configs. |
| D7 | Sessions | In-memory store behind a small interface, one turn at a time per session key, `onBusy` `WAIT` or `FAIL`. |
| D8 | Questions | Presets with `ask` map the harness question to `InputGateway.ask`; the node waits (see [ADR 0001](0001-input-requests.md)). |
| D9 | Compatibility | Alpha: renames and breaking config changes allowed; no aliases. Sandbox, secret and redaction hardening stays out of this phase; the seams are kept (#1960 section 6). |

## Consequences

- A new harness is a JSON preset, with no Java change (Codex is the proof; its real CLI flags must be checked, not assumed).
- Test without the vendor using a fixture harness script that speaks the preset protocol.
- Open: whether `stream-json` carries a stable resumable session id (pin it in the preset test), whether `maxTurns` is generic or per preset (default: per preset capability), and where operator presets live.
