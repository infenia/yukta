# 03 — CI pipeline gate

**Use case:** a CI-style gate. A check command runs; its exit code decides whether the merge is reported as allowed or blocked.

```
ONE_SHOT ──► PROCESS_EXECUTOR (routeByExitCode)
                 ├── success ──► report: PIPELINE PASSED ──┐
                 └── failure ──► report: PIPELINE FAILED ──┴──► CONSOLE_TERMINAL
```

The check is a stand-in (`echo`, `exit 1`). Replace `command` with `./gradlew check` or any real build step.

**Trigger**
```bash
curl -X POST localhost:8080/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-03-ci-pipeline","workflowId":"ci-pass"}'
```

**Run:** `./examples/03-ci-pipeline/run.sh` runs both `ci-pass` and `ci-fail`.

**Expected:** both runs finish `SUCCESS`; `ci-pass` prints `PIPELINE PASSED`, `ci-fail` prints `PIPELINE FAILED`.

**Notes:** `failureMode: CONTINUE` is required with `routeByExitCode`, otherwise a non-zero exit fails the whole run instead of taking the `failure` port.
