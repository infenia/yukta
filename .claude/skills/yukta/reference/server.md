# The dev server

One server per machine on `http://127.0.0.1:9858` (`YUKTA_DEV_PORT` overrides), shared by every checkout
and worktree; sessions are keyed by branch + checkout path, so checks never mix. It serves the web UI,
the REST API and MCP (`/mcp`) too.

- **Built from `origin/main`**, whatever branch you are on: `yk up` keeps a managed worktree of main in
  `~/.local/state/yukta-dev/main` (`YUKTA_DEV_HOME` overrides), builds `:boot:bootJar` + the CLI into
  `builds/<commit>/` (the three newest are kept) and runs the jar. Your branch's code is what the
  workflows run Gradle on; the server only orchestrates.
- **Fresh without interrupting**: the SessionStart hook runs `yk up --background`; when main moved, it
  builds the new commit and swaps the server only while no execution is running (`yk status` shows
  `stale … swaps when idle`). `yk check`/`yk gradle` start a stopped server and also make that swap.
- `yk up --release [tag]` runs a published release (native, cosign-verified) on the same port instead;
  `yk up` goes back to main. `YUKTA_SERVER_URL=<url>` points every `yk` command at another server.
- The CLI used is the one built with the server (`YUKTA_BIN=<path>` uses your branch's build when you
  are changing `cli/`).

## Files (`~/.local/state/yukta-dev/`)
`server.log`, `server.env` (what runs), `server.pid`, `up.log` (background `yk up`), `build.log`
(last main build), `sessions/`, `main/` (managed worktree: never edit it), `builds/`, `releases/`.
Per checkout, `.yukta-dev/` holds heal records, check logs, `durations.tsv` and hook marks.

## Troubleshooting
- `yk status` says `down` with `yk up in progress`: the first build of a commit takes minutes; wait or
  `tail ~/.local/state/yukta-dev/up.log`.
- Build failed: `build.log`. A broken main is a finding (`yk finding --module build`), and
  `yk up --release` gets you a working server meanwhile.
- `port 9858 is used by …`: another program holds the port; free it or set `YUKTA_DEV_PORT`.
