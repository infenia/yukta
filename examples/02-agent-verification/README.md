# Make an AI agent prove its work before "done"

## The pain point

A coding agent often says "done" after it has changed files but before the build and the tests
ran, or after it deleted or disabled a failing test to make things green. You want "done" to be
something the agent cannot say on its own: it exists only when every check ran and passed.

## The graph

```
verify-change:   trigger ─► attempts (LOOP_PREDICATE, max 3) ─► decide ─verified─► record-done ─► done  (OUTCOME SUCCESS)
                               │                                    └exhausted─► record-stop ─► stopped (OUTCOME ESCALATE)
                               ▼ runs the sub-workflow "attempt" until its outcome is SUCCESS
attempt:         trigger ─► build ─► test ─► patch-guard ─► coverage ─► coverage-gate ─pass─► verified ─► ok (SUCCESS)
                              │        │          │ reject       │ failure      │ fail
                              └────────┴──────────┴──────────────┴──────────────┴──► gate-failed ─► fix ─► retry (RETRY)
```

## What each node does

| Node | Plugin | Job |
|---|---|---|
| `attempts` | `LOOP_PREDICATE` over `SUB_WORKFLOW` | Runs `attempt` again while its outcome is not `SUCCESS`, at most 3 times |
| `build`, `test` | `PROCESS_EXECUTOR` | `sh build.sh` and `sh test.sh` in the project; a non-zero exit goes to `failure` |
| `patch-guard` | `PATCH_GUARD` | Judges the uncommitted change: `requireChange`, `noTestRemoval`, forbidden patterns (`@Disabled`, `@Ignore`, `.skip(`), protected files (the build and test scripts, `coverage.txt`) |
| `coverage`, `coverage-gate` | `PROCESS_EXECUTOR`, `BRANCH` | Reads `{"coverage": N}` from `coverage.sh`; N below 80 fails |
| `gate-failed`, `fix` | `MAPPER`, `PROCESS_EXECUTOR` | Any failed gate calls the fixer and ends the attempt with `RETRY` |
| `ok`, `retry` | `OUTCOME_TERMINAL` | Report how the attempt ended to the parent loop |
| `decide`, `record-done`, `done` | `BRANCH`, `PROCESS_EXECUTOR`, `OUTCOME_TERMINAL` | Only a `SUCCESS` outcome records "done" |
| `record-stop`, `stopped` | `PROCESS_EXECUTOR`, `OUTCOME_TERMINAL` | Retry budget used up: hard stop, outcome `ESCALATE`, no "done" |

The paths in `session.json` point at `/tmp/yukta-example-agent-verification/` (the project in `repo/`, the fixer in `fixer.sh`),
because `PATCH_GUARD` does not resolve `${...}` in `dir`.

## Make it yours

- Project and gates: set `workingDir` and the commands of `build`, `test` and `coverage` (for example `["./gradlew","build"]`), and `dir` of `patch-guard`. The server must allow those executables (`yukta.process.allowed-commands`).
- Guard rules: `protectedPaths`, `forbidPatterns`, `noTestRemoval`, `requireChange`.
- Coverage threshold: the two cases of `coverage-gate`.
- Retry budget: `maxIterations` on `attempts`.
- LLM: `fix` is the stand-in for the agent. Replace it with `LLM_CALL` (give it the failing output in the prompt and have a later node apply its patch) or a command that starts your agent CLI, for example `["claude","-p","fix the failing gate"]`.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-02-agent-verification","workflowId":"verify-change","payload":{}}'
```

## How to run

Start the server with `yukta.process.allowed-commands=sh,echo` (environment: `YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo`). The server also needs `git` on its path for `PATCH_GUARD` (it is not a `PROCESS_EXECUTOR` command), and `git` must be installed where `run.sh` runs.
Replace the list with your real executables when you swap in your own commands.

```bash
./examples/02-agent-verification/run.sh
```

`run.sh` builds a small git project per scenario and plays the agent: clean change, failing test fixed on retry,
deleted test caught by the guard, no change at all, and coverage 55 that nobody can fix.
It exits 3 (SKIP) when the server does not allow commands.

## Expected result

Ends with `PASS 02-agent-verification`. The first three scenarios record "done" (after 1, 2 and 2 attempts).
The last two stop hard after 3 attempts with outcome `ESCALATE` and never record "done".
