# 19 — A CI pipeline

**Use case:** the checks you would put in a CI file, as a workflow you can run anywhere Yukta runs: clone the project, check the backend (lint, format, test) and, once that passes, the frontend (install, lint, format, test). The project is [yukta-example-todo](https://github.com/infenia/yukta-example-todo), the one [example 18](../18-todo-run/) runs.

```
API_TRIGGER ─► check-python ─► clone ─► backend-lint ─► backend-format ─► backend-test ─► probe-js ─► BRANCH
 (no python: NOTIFY_TERMINAL)                                                                          │
   bun  ─► bun-install ─► bun-lint ─► bun-format ─► bun-test ─► passed-bun ◄───────────────────────────┤
   node ─► node-install ─► node-lint ─► node-format ─► node-test ─► passed-node ◄──────────────────────┤
   none ─► NOTIFY_TERMINAL (inbox)  ◄──────────────────────────────────────────────────────────────────┘
```

| Stage | Command | Tool |
|---|---|---|
| `check-python` | `uv python find` (no Python: a message in the inbox, and the run ends) | uv |
| `clone` | `git clone --depth 1 <repo> <dir>` | git |
| `backend-lint` | `uv run ruff check .` | ruff |
| `backend-format` | `uv run ruff format --check .` | ruff |
| `backend-test` | `uv run pytest -q` | pytest |
| `probe-js` | is `bun`, else `node`, on the PATH? (neither: a message in the inbox) | Python one-liner |
| `bun-install`, `bun-lint`, `bun-format`, `bun-test` | `bun install --frozen-lockfile`, `bun --bun run lint`, `bun --bun run format:check`, `bun test` | bun, Biome |
| `node-install`, `node-lint`, `node-format`, `node-test` | `npm install`, `npm run lint`, `npm run format:check`, `npm test` | node, Biome |
| `passed-bun`, `passed-node` | `CONSOLE_TERMINAL` | ends the run |

Every stage is a `PROCESS_EXECUTOR` with the default `failureMode: ERROR`: a stage that exits non-zero fails the run, and the stages after it do not start, which is what a CI job does. Open the run in the UI to see which stage ran, how long it took and what it printed.

**Needs:** `git`, [`uv`](https://docs.astral.sh/uv/) (it finds your Python, or `uv python install` gets one), and [`bun`](https://bun.sh) or [`node`](https://nodejs.org) on the machine that runs the server, and a server that allows them (`npm.cmd` instead of `npm` on Windows):

```bash
YUKTA_PLUGINS_ENABLED=all YUKTA_PROCESS_ALLOWED_COMMANDS=git,uv,bun,node,npm ./gradlew bootRun
```

**Run:** `./examples/2-free-deps/19-todo-ci/run.sh` (Linux, macOS and Windows with Git Bash). The first run downloads the packages (about a minute); later ones are quicker. Expected:

```
    stages:
      SUCCESS	check-python
      SUCCESS	clone
      SUCCESS	backend-lint
      ...
      SUCCESS	passed-bun
    PASS ci: every stage passed
```

If a tool is missing, the run puts a message in the inbox and `run.sh` prints it and ends with `SKIP` (exit 3), not with a pass.

**Try a failure:** clone a branch with a lint error. Fork the todo app, break a file, and put your fork's URL in `payload.json`: the run ends `FAILURE` at the first stage that notices, and the logs show the tool's own message.

**Make it yours:** swap `repo` in `payload.json` and the commands of the stages for your own. To run it on every push, start it from your Git host with `POST /api/workflow/start`, or from a `SCHEDULE_TRIGGER`.
