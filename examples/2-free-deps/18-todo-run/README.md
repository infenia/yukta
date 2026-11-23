# 18 — Check out and run an app

**Use case:** check a project out and run it so a person can open it: clone the todo app into a temp folder, then start its Python backend and React frontend.

A simpler cousin of [how Yukta develops itself](https://github.com/infenia/yukta-dogfood). The project is [yukta-example-todo](https://github.com/infenia/yukta-example-todo), a todo app with a Python backend and a React frontend.

```
                       ┌─ no python ─► NOTIFY_TERMINAL (inbox)
API_TRIGGER ─► check-python ─► clone ─┬─► backend: uv run app.py ──────────────────────► CONSOLE_TERMINAL
                                      └─► probe-js ─► BRANCH ─ bun ──► install ─► bun --bun run dev ─► CONSOLE_TERMINAL
                                                          ├─ node ─► npm install ─► node vite ───────► CONSOLE_TERMINAL
                                                          └─ none ─► NOTIFY_TERMINAL (inbox)
```

| Node | Plugin | What it does |
|---|---|---|
| `trigger` | `API_TRIGGER` | starts the run; it needs no input |
| `check-python` | `PROCESS_EXECUTOR` | `uv python find`: is there a Python? If not, `notify-python` puts a message in the inbox and the run ends |
| `clean`, `clone` | `PROCESS_EXECUTOR` | remove an earlier copy, then `git clone --depth 1 https://github.com/infenia/yukta-example-todo.git <temp folder>/yukta-todo-run` |
| `probe-js`, `js-runtime` | `PROCESS_EXECUTOR`, `BRANCH` | a one-line Python probe says whether `bun`, else `node`, is on the server's PATH; the branch picks the bun or the node frontend, or `notify-js` tells the inbox that neither exists |
| `backend` | `PROCESS_EXECUTOR` | starts the Python API on port 8000; it runs until the run is stopped |
| `install-*`, `frontend-*` | `PROCESS_EXECUTOR` | installs the packages (bun, or npm), then starts the React page (`bun --bun run dev`, or `node` and Vite) on port 5173, which proxies `/api` to the backend |

The two servers are long-running processes, so the run stays `RUNNING` while the app is up (their `timeout` is one hour). **Stopping the run stops both servers**: `POST /api/executions/<id>/stop?mode=KILL` (the script does this when you press Enter).

**Needs:** `git`, [`uv`](https://docs.astral.sh/uv/) (it finds your Python, or `uv python install` gets one), and [`bun`](https://bun.sh) or [`node`](https://nodejs.org) on the machine that runs the server, free ports 8000 and 5173, and a server that allows them (`npm.cmd` instead of `npm` on Windows):

```bash
YUKTA_PLUGINS_ENABLED=all YUKTA_VARIABLES_ALLOWED=java.io.tmpdir YUKTA_PROCESS_ALLOWED_COMMANDS=git,uv,bun,node,npm ./gradlew bootRun
```

`YUKTA_VARIABLES_ALLOWED=java.io.tmpdir` lets the workflow read the server's temp folder, which is where it clones (`/tmp` on Linux, `%TEMP%` on Windows); it stays there between runs and the next run replaces it.

**Run:** `./examples/2-free-deps/18-todo-run/run.sh`. It works on Linux, macOS and Windows (use Git Bash). It starts the run, waits until the page answers, adds a todo through the page's own `/api` proxy, and then leaves the app up:

```
    PASS: the page and the backend behind it answer (http://127.0.0.1:5173)

    Open http://127.0.0.1:5173 and try it. Press Enter to stop the run (and both servers).
```

If a tool is missing, the run puts a message in the inbox (`Python was not found`, or `Neither bun nor node was found`) and `run.sh` prints it and ends with `SKIP` (exit 3). A missing `uv` or `git` stops the run with `Cannot run program`; `run.sh` skips for `git`, and the server log names `uv`.

Open the page, add and tick a few todos, then press Enter: the run is stopped. `HOLD=0` (or no terminal, as in CI) stops it at once.

**Make it yours:** change the URL in the `clone` node to your own project (a fork of the todo app works as is), and the `command` and `workingDir` of `backend`, `install` and `frontend` to how your project starts. Replace a command with the one your stack uses (`npm run dev`, `go run .`, `docker compose up`); allow it with `YUKTA_PROCESS_ALLOWED_COMMANDS`.

The next example, [19](../19-todo-ci/), runs the same project's checks as a pipeline.
