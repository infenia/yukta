# yukta (Claude Code mod)

A live view of your Yukta server inside Claude Code.

- `/yukta` opens a pane: running executions (Stop) and pending approvals (Approve / Reject)
- toasts when a run ends or waits for approval
- a band above the prompt while an approval waits
- the Yukta MCP server (`mcp__plugin_yukta_yukta__*` tools)

## Install

```
/plugin install yukta --marketplace infenia/yukta
```

Then set the options in `/config` (Plugins): `server_url` (default `http://localhost:9858`), `api_key` (sent as `X-API-Key`),
`headers_command` (optional, prints a JSON object of headers; wins over the key), `poll_seconds`, `show_status` (off by default: a status-line entry such as `yukta ✓ 2 running`).

The MCP server uses `server_url` and `api_key` only, and always sends the `X-API-Key` header (empty when no key is set; a server that rejects an empty key needs one, or your own `.mcp.json` entry instead). A server that needs dynamic headers is better added with your own `.mcp.json`.
