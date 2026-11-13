# Drive Yukta with `yukta-cli` (no MCP tools)

Use this when the `yukta` MCP tools are not available. The CLI does the same things; every command takes
`--url <server>` (default `http://localhost:9858`, env `YUKTA_SERVER_URL`) and, for a server with keys, `--token-file <file>` or the env var
`YUKTA_TOKEN`. Prefer those over `--token` (it lands in shell history). Add `-o json` to get JSON you can parse.

Start with `yukta-cli whoami`: it shows your role. A command your role does not allow fails with exit code 1 and an HTTP 403 message.

| Goal | Command |
|---|---|
| Who am I, what may I do | `yukta-cli whoami` |
| List / read sessions | `yukta-cli session list`, `session get <id>`, `session config <id>` |
| Validate / apply a session (ADMIN) | `yukta-cli session validate <json-or-file>`, `session apply <json-or-file>` |
| Plugins a workflow may use | `yukta-cli plugin list`, `plugin get <type>` |
| Start a run | `yukta-cli workflow start <session> <workflow> [--payload-file f] [--idempotency-key k]` |
| Status, logs, result | `workflow status <session> <exec>`, `logs <session> <exec>`, `result <session> <exec>` |
| One node's output | `logs <session> <exec> --node <id> --tail 100 [--grep re]` |
| Steer | `workflow pause\|resume <session> <exec>`, `workflow executions stop\|restart\|recover <exec>` |
| Roll back a version | `session workflow versions\|diff\|rollback <session> <workflow> ...` |
| Pending approvals | `yukta-cli inputs list`, `inputs answer ...` (see `yukta-cli inputs --help`) |

Anything else: `yukta-cli <command> --help`. The full list is in the CLI reference of the docs (`docs/reference/cli.mdx`).

## Exit codes

`0` ok, `1` other failure, `2` usage error, `3` not found, `4` server error, `5` timeout, `6` refused by a supervisor. A `6` is a decision, not
an error to retry: tell the user which supervisor refused.

## Same rules as the MCP skill

Ask the user before replacing or deleting a session, force-stopping runs, rolling back, changing scheduler limits or answering an approval.
Never print or store a token.
