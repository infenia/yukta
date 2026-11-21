# 17 — Ask a random question

**Use case:** ask a person yes or no; on `no` the run stops, on `yes` it asks a random question and prints the question and the answer.

```
CONSTANT_SOURCE ─► ASK_INPUT (yes/no) ─► BRANCH ─no──► CONSOLE_TERMINAL
                                           └─yes─► PROCESS_EXECUTOR (shuf) ─► MAPPER ─► ASK_INPUT (text) ─► PROCESS_EXECUTOR (echo) ─► CONSOLE_TERMINAL
```

**Trigger**
```bash
curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @session.json
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-17-ask-question","workflowId":"ask"}'
```
Then answer in the UI (the Inputs panel of the run), or run `./run.sh`, which answers both requests itself.

**Expected:** status `SUCCESS`; on yes the log line `STDOUT: Question: <question> | Answer: <your answer>`; on no no such line.

**Notes:** the question is picked by `shuf` (`PROCESS_EXECUTOR`) and cleaned by a `MAPPER`; swap them for an `LLM_CALL` to generate real ones. The `echo` is the "terminal output" (`CONSOLE_TERMINAL` logs payloads only at `DEBUG`), so the server needs `YUKTA_PROCESS_ALLOWED_COMMANDS=echo,shuf`.
