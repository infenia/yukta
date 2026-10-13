# Dogfood backlog

Defects, errors and slowdowns noticed while Yukta ran its own checks (see
`examples/11-dogfood/README.md`). Fix now or later; mark `Status: fixed in <sha>` when done.

### DF-1: Every node is stamped RUNNING and started at wiring time, so node timings are cumulative
- Status: open        - Severity: P3        - Kind: ux
- Found: 2026-10-13, workflow gradle-check, scope messaging, execution b7b6f80b-5fa3-4afa-9556-81043bb6481a, stage all
- Evidence: the first events of a run are `NODE done RUNNING`, `NODE security RUNNING`, ... `NODE trigger RUNNING`
  (reverse order, all within ~1 ms) while the stages then run one after another. In `GET /api/executions/{e}`
  every task has `startTime` = the run start (03:11:03.62x) and `endTime` = when it finished, so `done`, which does
  no work, reports 23 s and each stage reports the time since the run began.
- Repro: `examples/11-dogfood/scripts/yukta-check.sh --watch -m messaging`, then
  `examples/11-dogfood/scripts/yukta-cli.sh -o json workflow status <session> <execution> | jq '.data.tasks[] | [.nodeId,.startTime,.endTime]'`
- Impact: `RUNNING` does not mean a node has input or is doing work; per-node duration from the progress API is
  wrong for any sequential graph (the dogfood `SLOW` detector works around it with end-time gaps). Related to the
  known "node timeout starts at wiring" finding in `plans/09-backlog.md`.
