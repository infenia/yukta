# Dogfood loop: watch, steer, record

Yukta develops Yukta, so every run is also a test of Yukta.

## Watch
Start detached and let the cheap `yukta-run-watcher` subagent (Haiku) block on it, spawned with
`run_in_background` and the ids; it returns one `RESULT` line, or a ≤12-line L1 triage (failed node,
classification, key lines, next step) or an `ESCALATE:` with the evidence. Stay silent on routine progress.
```bash
yk check --detach [-m <module>]       # STARTED session=<s> log=<file>
yk gradle --detach <gradle args>      # STARTED execution=<e> session=<s>
```
Without the subagent, run `yk watch <s> <e|log>` under the Monitor tool. Lines: `NODE <node> <status>`
(failed nodes), `ERROR <node>: <line>`, `WARN <node>: <line>`, `CONTROL <cmd> applied=false …`,
`WORKFLOW <status>`, `SLOW <node> …`, `RESULT …`. `yk watch … --all` shows every event (only to debug a
stage live; never monitor it, compile warnings alone flood it).

## Triage every failure or anomaly (root cause, not symptom)
Start from the failed stage's `ERROR` lines, then:
- **Caused by your change / blocks this run**: fix it, then rerun. `yk retry <e> <node>` takes a node of
  that execution: `gradle` for a `yk gradle` run; in the staged check each stage is its own workflow
  (`STAGE <stage> … execution=<e>`), whose stage node is `run`, so `yk retry <e> run`. Rerunning
  `yk check` is usually simpler: passing stages are up to date and fast.
- **Flaky/env** (JVM crash, port clash): retry once; if it recurs, it is a defect: record it.
- **Unrelated Yukta issue**: don't fix it inline; record it and carry on.
Also record what you notice while watching: broken functionality, monitoring/execution gaps, performance,
security. Stop a run early once a stage shows an `ERROR` you cannot be wrong about (`yk stop <e>`).
Other levers, only on executions you started: `yk cli workflow pause|resume <s> <e>`,
`yk cli workflow node pause|resume|stop|skip <s> <e> <node>`,
`yk cli workflow node signal <e> <node> --name N --decision D`,
`yk cli workflow node breakpoint <e> <node> --field F --equals V [--clear]`.
If the Go CLI or `yk` falls short (missing flag, filter or output), improve it in `cli/` or
`examples/11-dogfood/` as part of the work (`:cli:check` needs 100% function coverage).

## Record a finding
```bash
yk finding --title "<one-line title>" --module <module> --priority P0|P1|P2|P3 \
  --kind bug|error|perf|ux|security|improvement --workflow <wf> --execution <e> \
  --evidence '<the ERROR / WARN / CONTROL / SLOW / RESULT line>' \
  [--repro '<command>'] [--expected '<what should have happened>'] [--dry-run]
```
It files a GitHub issue labelled `dogfood` (triaged onto the Yukta Roadmap project); an open issue with the
same title gets the evidence as a comment, so reuse the title for a repeat. `--module` is where the defect
is (the dogfood tooling itself: `build`). Check first: `gh issue list -l dogfood`.
Record: an unexpected `CONTROL … applied=false`; an exception not from your change; a watcher that never
ends or a RESULT that contradicts node statuses; a CLI/server error; a `SLOW` line; a tooling failure.
Filing is visible to everyone: file without asking only during a dogfood run you were asked to do, and
list the issues you filed or updated in your reply. Close one you fix from the PR with `Fixes #<n>`.
