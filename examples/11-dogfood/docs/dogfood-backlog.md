# Dogfood backlog (dogfood tooling)

Defects and rough edges in the dogfood scripts and workflows themselves. Yukta defects found by the
loop go to the module's own `docs/dogfood-backlog.md` (e.g. `core/docs/dogfood-backlog.md`).

### DF-5: the long-running server on :8080 died during a `--watch` run
- Status: open        - Severity: P2        - Kind: error
- Found: 2026-10-13, workflow quick-check, execution f44043ba-1735-4b47-b093-826c7f3ced92
- Evidence: after `yukta controlbus watch` failed with `unexpected EOF`, `localhost:8080` refused connections and the process was gone; no log was captured (started from a terminal). Not reproduced on a probe server (survived DF-3).
- Repro: unknown.
- Update 2026-10-14: not reproduced. A native server on :9101 survived two full `gradle-check` runs plus watchers after the DF-3 fix (it died only while DF-3 broke the stream). Native servers now log to `.yukta-dev/<name>.log`. Reopen with that log if it recurs; the suspect remains the old native build.
