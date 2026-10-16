# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Known issues found by the perf tests

Logged, not fixed. Found 2026-10-11 on `main` at `6070249e`.

## 1. Runs never finish: `CONSOLE_TERMINAL` stays `RUNNING`

- **Symptom:** `GET /api/executions/{id}` returns `status: RUNNING` and `endTime: null` forever. Every
  upstream node is `SUCCESS`; only the terminal node is `RUNNING`. `/api/workflow/{session}/history` also
  shows the run as `RUNNING`.
- **Repro:** `POST /api/sessions` with `CONSTANT_SOURCE -> CONSOLE_TERMINAL` (config `{}`), `POST /api/workflow/start`,
  poll the execution for 30 s. Same with `CONSTANT_SOURCE -> MAPPER -> CONSOLE_TERMINAL`, with `API_TRIGGER` as the
  trigger, and with `displayOutput: true`. Profile `prod`, in-memory stores, default limits.
- **Evidence:** the log has "Starting workflow execution" and "Started tracking" for the run but no
  "Consumed N message(s)" line (`ConsoleTerminalPlugin.consume`, INFO; the payload itself is logged at DEBUG as "Consuming message") and no "Terminal node execution completed
  successfully" (`TerminalNodeAssemblerStrategy`), so the terminal does not seem to receive the message or to
  complete. Not investigated further.
- **Impact:** executions pile up as `RUNNING`; under load memory and thread use grow, and the soak test
  cannot tell a healthy run from a stuck one. The scripts work around it with `LENIENT=true` (done when all
  non-terminal nodes ended; counted in `workflow_lenient_done`). Use `-e LENIENT=false` after the fix.

## 2. Fan-out: one of two children of the same trigger never finishes

- **Symptom:** workflow `CONSTANT_SOURCE -> MAPPER a`, `CONSTANT_SOURCE -> MAPPER b`, both `-> CONSOLE_TERMINAL`. After
  3 s node `b` is `SUCCESS` but node `a` stays `RUNNING`, so the run never ends. 0 of 53 runs completed in the
  load test, while single-path chains of the same nodes (including 10 in a row) always complete.
- **Repro:** `WORKFLOWS=fanout k6 run perf/k6/load.js` (this is not in the default set), or start the
  `k6-fanout` session by hand. Not investigated further; possibly related to #1 (merged terminal) or to two
  edges leaving one output port.

## 3. `bootRun` needs the UI build

`./gradlew bootRun` fails at `:ui:pnpmInstall` when pnpm cannot be downloaded (offline/sandbox). Workaround:
`-x :ui:pnpmInstall -x :ui:pnpmBuild -x :ui:pnpmSetup -x :ui:nodeSetup` (API only).
