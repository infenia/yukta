# yukta-channel

A generic [Claude Code channel](https://code.claude.com/docs/en/channels-reference.md) (research preview): an MCP server over stdio
that Claude Code spawns, with an HTTP API on `127.0.0.1` in front of it. Anything that can make an HTTP call (a script, a webhook, a
workflow node, a chat bridge) can send an event into a running session and read what Claude sends back.

```
caller ── POST /v1/events ──▶ channel ── notifications/claude/channel ──▶ Claude Code session
caller ◀── GET /v1/stream ─── channel ◀── reply / done tools, permission prompts ── session
```

Bun and TypeScript; dependencies are `@modelcontextprotocol/sdk` and `zod` (`bun install`, lockfile `bun.lock`).

## Use it

Register it as an MCP server and start Claude Code with the development flag (a custom channel is not on the allowlist; the flag needs an
interactive session, `claude -p` ignores it):

```json
{ "mcpServers": { "yukta-channel": { "command": "bun", "args": ["run", "/abs/path/to/channel/src/main.ts"],
  "env": { "YUKTA_DEV_HOME": "/home/me/.local/state/yukta-dev", "YUKTA_CHANNEL_NAME": "yukta-channel" } } } }
```

```bash
claude --mcp-config channel.json --dangerously-load-development-channels server:yukta-channel
```

Then, with the token in `$YUKTA_DEV_HOME/channel-token` (created 0600 at the first start) and the port from the registry entry
`$YUKTA_DEV_HOME/channels/<name>.json`:

```bash
curl -s -X POST localhost:$PORT/v1/events -H "Authorization: Bearer $(cat $TOKEN)" \
  -d '{"content":"build failed on main","meta":{"severity":"high"}}'          # {"event_id":"3f9a1c2e"}
curl -sN localhost:$PORT/v1/stream -H "Authorization: Bearer $(cat $TOKEN)"    # what Claude answers
```

Claude sees `<channel source="yukta-channel" severity="high" event_id="3f9a1c2e">build failed on main</channel>` and answers with its
`reply` tool; the answer arrives on the stream as `event: reply`.

## API (all under `/v1`; the full contract is `GET /v1/openapi.json`)

| Route | Auth | |
|---|---|---|
| `GET /v1/status` | no | `connected`, `busy`, the event in flight, open permission prompts |
| `GET /v1/openapi.json` | no | OpenAPI 3.1 |
| `POST /v1/events` | bearer | `{content, meta?, sender?, idempotency_key?}` -> `202 {event_id}`; `200` duplicate; `400` invalid; `401`; `403` sender; `409` busy (exclusive); `413`; `503` not connected |
| `GET /v1/stream` | bearer | server-sent events `event`, `reply`, `done`, `permission_request`, `permission_resolved`; `Last-Event-ID` or `?since=` replays the last 200 |
| `GET /v1/permissions`, `POST /v1/permissions/{id}` | bearer | permission relay (below) |

- `meta` keys become attributes of the `<channel>` tag and must be letters, digits and underscores (Claude Code drops others silently, so the API
  answers `400` instead). `event_id` and `sender` are set by the channel.
- Claude Code does not acknowledge events: a `reply` or `done` on the stream is the only delivery signal.
- Events queue in Claude Code while it is busy and arrive together on its next turn.

## Claude's side (MCP tools)

- `reply {text, event_id?, meta?}`: a message for the caller; `event_id` is the attribute of the tag it answers.
- `done {event_id?, outcome?, note?}`: the event is handled. On an **exclusive** channel it frees the channel (otherwise `POST /v1/events` answers
  `409` until `done`, or until the busy limit).

## Permission relay (optional)

`YUKTA_CHANNEL_PERMISSIONS=on` declares `claude/channel/permission`: tool approval prompts (`Bash`, `Write`, ...) appear on the stream as
`permission_request` (`request_id`, `tool_name`, `description`, `input_preview`: untrusted text) and are answered with
`POST /v1/permissions/<request_id>` `{"behavior":"allow"|"deny","sender":"<id>"}`. The terminal dialog stays open and the first answer wins.
Anyone who can answer can approve a tool call, so it only starts with a `YUKTA_CHANNEL_SENDERS` allowlist, and only a listed sender can answer.

## Security

The token is required for everything that sends or reads content; the server listens on `127.0.0.1` only. An ungated channel is a prompt injection
path: the content of an event is untrusted input to Claude. `YUKTA_CHANNEL_SENDERS` (comma-separated ids) additionally requires `sender` on every
event to be on the list: gate on the **sender**, not on a room or channel.

## Configuration (environment)

| Variable | Default | |
|---|---|---|
| `YUKTA_CHANNEL_NAME` | `yukta-channel` | MCP server name: the `source` of the tag and the registry name |
| `YUKTA_CHANNEL_INSTRUCTIONS` | generic text | what Claude is told about the events and tools |
| `YUKTA_CHANNEL_PORT` | `0` | HTTP port; `0` takes a free one |
| `YUKTA_CHANNEL_EXCLUSIVE` | off | `on`: one event in flight |
| `YUKTA_CHANNEL_BUSY_LIMIT` | `14400` | seconds after which an exclusive event frees itself |
| `YUKTA_CHANNEL_SENDERS` | none | allowed sender ids |
| `YUKTA_CHANNEL_PERMISSIONS` | off | `on`: permission relay |
| `YUKTA_CHANNEL_TOKEN_FILE` | `$YUKTA_DEV_HOME/channel-token` | the bearer token |
| `YUKTA_DEV_HOME` | `~/.local/state/yukta-dev` | state: token, registry |
| `YUKTA_CHANNEL_PROFILE` | generic | `pickup`: the profile below |

The channel registers `{name, port, pid, started}` in `$YUKTA_DEV_HOME/channels/<name>.json` (a live channel with the name makes the next one
`<name>-<pid>`) and removes it when the session ends. Claude Code gives an MCP server a filtered environment, so put these variables in the `env` of
its `mcpServers` entry, as above.

## The `pickup` profile

`YUKTA_CHANNEL_PROFILE=pickup` is what `yk pickup session` starts: name `yukta-pickup`, exclusive, token `pickup-token`, registry `pickup/`, and one tool,
`pickup_done {issue, outcome: pr|blocked|skipped, url?, note?}`, which frees the channel and appends to `pickup.jsonl`. `scripts/pickup.sh tick` sends the
issue as `{content, meta: {issue, url, title}}`. Environment: `YUKTA_PICKUP_NAME`, `YUKTA_PICKUP_PORT`, `YUKTA_PICKUP_BUSY_LIMIT`. See `src/pickup.ts`:
a new profile is a name, a token and registry location, instructions, and optional tools.

## Develop

```bash
yk tool -C examples/11-dogfood/channel bun test                      # unit tests (in-memory MCP client) and the stdio end-to-end test
yk tool -C examples/11-dogfood/channel node_modules/.bin/tsc --noEmit
yk tool bash examples/11-dogfood/scripts/test-pickup.sh              # pickup.sh tick against real channels
```

| File | |
|---|---|
| `src/channel.ts` | the core: MCP server, event submission, exclusive mode, tools, permission relay, event bus |
| `src/http.ts`, `src/openapi.ts` | the HTTP API and its OpenAPI document |
| `src/registry.ts` | the registry of running channels |
| `src/pickup.ts` | the pickup profile |
| `src/main.ts` | the entry point and the environment |
