# Known issues found by the perf tests

Logged, not fixed. Found 2026-10-11 on `main` at `6070249e`; updated 2026-10-21.

## 1. Runs never finish: `CONSOLE_TERMINAL` stays `RUNNING` (fixed)

Cause was a broadcast subscriber count that was only known once a subscriber arrived; fixed in #331. Checked
2026-10-21 on `3fcfc7a9` plus the fix below with `LENIENT=false`: 0 stuck runs in 20,000+ runs.

## 2. Fan-out: one of two children of the same trigger never finishes (fixed)

Same cause as #1. `fanout` is part of the 250 starts/s check above and finished every run.

## 3. `bootRun` needs the UI build

`./gradlew bootRun` fails at `:ui:pnpmInstall` when pnpm cannot be downloaded (offline/sandbox). Workaround:
`-x :ui:pnpmInstall -x :ui:pnpmBuild -x :ui:pnpmSetup -x :ui:nodeSetup` (API only).

## 4. Runs stuck as `RUNNING`, control calls answer 404 (fixed, DF-7)

Under load on 2 vCPU the tracker lost status events (`Tracker event lost`), so finished runs stayed `RUNNING`
and Stop/Pause/Restart answered 404. The k6 tests did not notice because `LENIENT` defaulted to true. Now
`stuck_runs` and `tracker_events_lost` must be 0. See `docs/internal/bugs.mdx` DF-7.

## 5. A started run is unreadable for a moment (open, DF-8)

`GET /api/executions/{id}` answers 404 for a few ms after `POST /api/workflow/start` returned 202, about 1% of
starts at 250 starts/s. The tests retry and count it as `poll_not_found`.

## 6. Native image: the history endpoint answers 500 (open, #724)

`MODE=native` only. `GET /api/workflow/{id}/history?status=RUNNING&limit=1000`, which the `stuck_runs` check
calls, fails with `Record components not available for record class ...HistoryFilter`: the record is missing from
the native reflection config. It works on the jar.
