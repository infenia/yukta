# Steering a run (T0)

The interactive session controls any tree of runs; headless fixers and implementers have no key and no control.
Steer only runs you started or that the user asked you to steer.

## See
```bash
yk tree <e>                  # the run and every run below it: id, workflow, status, [current nodes], awaiting <signal>@<node>
yk signals [<e>]             # what waits for an answer (one execution's tree, or every session with a live run)
yk watch <s> <e> --all       # every event of a run (people only; never monitor it: compile warnings flood it)
```
`<e>` may be the first 8 characters of an execution id (the tree prints those). A paused run shows
`RUNNING paused`: its status stays RUNNING, its control state says PAUSED.
Live feed for a long run: `yk watch <s> <e>` under the Monitor tool (problems only, descendants included), or the
`yukta-run-watcher` subagent. The status line shows the server, the live runs, the pending answers and the last
result of this checkout. MCP (`mcp__yukta__*`, no setup: `.mcp.json` takes the key from `yk mcp-headers`) gives the
same data structured, for drill-down: `get_execution_tree`, `get_execution_logs` (regex + tail), `get_execution_result`,
`list_pending_approvals`; its control tools (`control_workflow`, `control_node`, `signal_node`) are not pre-approved.

## Act
```bash
yk pause <e> [--tree]        # --tree: and every run it started as a sub-workflow
yk resume <e> [--tree]
yk stop <e> [--tree]
yk skip|unskip|retry <e> <node>
yk break <e> <node> --field payload.status --equals error     # hold a node on a value; --clear removes it
yk step <e> <node> on|off|next
yk approve <e> [signal] [--data text]     yk reject <e> [signal]
```
- **Pause vs stop.** Pause to look, to let the user decide or to edit something the run reads: nothing is lost and
  `resume` continues. Stop to give up on the run (it ends CANCELLED and cannot continue; start a new one).
  Pausing the parent alone leaves a child it waits on running: use `--tree`.
- **Retry** re-runs a node that ended FAILURE or TIMED_OUT, with everything behind it. A healing check already
  retries its stages; retry a stage that its ladder gave up on only after you fixed the cause.
- **An epic** (`yk run epic <n>`) waits at `between-issues` before each issue; two ESCALATEd issues pause the tree by themselves:
  look at `yk tree <e>` and the HEAL / NODE lines of the failed issues, fix the cause (or the briefs), then `yk resume <e> --tree`,
  or `yk stop <e> --tree`. A budget (`YUKTA_BUDGET_ISSUE`, `YUKTA_BUDGET_EPIC`) is not a signal: the run just ends ESCALATE; raising it
  means starting `yk run issue <n>` again with a bigger one.
- **Signals.** `yk check --pause before-opus` waits in front of the opus fixer. `approve` lets the expensive rung run
  (the cheaper rungs said fixed=false: read their HEAL summaries first); `reject` ends the run FAILURE; doing nothing
  lets it go on after `YUKTA_PAUSE_SECONDS` (600). To take over instead, `stop` the run, fix the code yourself, rerun.
  Answer only signals the user would expect you to answer; ask for an approval that spends money beyond the budget.
