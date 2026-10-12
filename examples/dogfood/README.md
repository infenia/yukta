# Dogfood: develop Yukta with Yukta

Run Yukta's own `gradle check` as a Yukta workflow, so Claude Code (or you) *reads one
deterministic result* instead of choosing which checks to run.

```
main ──worktree──▶ ../yukta-dev-<name>   Yukta server (own port, own state)
                          ▲
feature branch ──CLI──────┘   yukta session apply → workflow start → logs → RESULT line
```

## Prerequisites
`git`, `jq`, `curl`, Java 25, Go (for the CLI). Optional: `opengrep`, `trivy` (skipped when absent).

## Quick start
```bash
./gradlew :cli:goBuild                                        # builds cli/build/yukta-<os>-<arch>

examples/dogfood/scripts/yukta-worktree.sh dev                # worktree from main + server on a free port
examples/dogfood/scripts/yukta-check.sh                       # check the current checkout
examples/dogfood/scripts/yukta-stop.sh dev                    # stop server, remove worktree
```

`yukta-check.sh [project-path]` exits 0 only when the execution ends `SUCCESS` (or `COMPLETED`); its last line is:

```
RESULT status=FAILED failed_node=style execution=<id> session=check-<branch>
```

## The check workflow
`workflows/check.session.json` is `ONE_SHOT` → one `PROCESS_EXECUTOR` per stage → `CONSOLE_TERMINAL`.
Stages run sequentially and fail fast (avoids concurrent Gradle runs fighting over one build dir):

| node | gradle tasks | reports |
|---|---|---|
| `spotless` | `spotlessCheck` | console |
| `compile` | `compileJava compileTestJava` | console |
| `tests` | `test jacocoTestReport` | `<module>/build/test-results`, `build/reports/tests` |
| `coverage` | `jacocoTestCoverageVerification` | `<module>/build/reports/jacoco` |
| `style` | `checkstyle{Main,Test} pmd{Main,Test}` | `<module>/build/reports/{checkstyle,pmd}` |
| `spotbugs` | `spotbugs{Main,Test}` | `<module>/build/reports/spotbugs` |
| `licenses` | `licensee` | console |
| `security` | `opengrep trivy` | `<module>/build/reports/{opengrep,trivy}` |
| `cli` | `:cli:check` | console |

`failed_node` tells you which stage broke; open only that stage's report.

## Using the CLI directly
```bash
export YUKTA_SERVER_URL=http://localhost:9101     # printed by yukta-worktree.sh
yukta session list
yukta -o json workflow status <session> <execution>
yukta logs <session> <execution>
yukta plugin list
```
The server also exposes the web UI and MCP on the same port.

## Notes
- State lives in `.yukta-dev/` (git-ignored): `<name>.env`, `.pid`, `.log`, and `current.env` (last server).
- Per-worktree isolation comes from `SERVER_PORT` and `YUKTA_SESSION_BASE_DIR`.
- Sessions are in-memory: restarting the server forgets them; `yukta-check.sh` re-applies each run.
- Edit stages in `workflows/check.session.json`; `__PROJECT_PATH__` is substituted at run time.
- Handy Claude Code permissions: `Bash(examples/dogfood/scripts/*)`, `Bash(yukta *)`.
