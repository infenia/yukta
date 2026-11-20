# 01 — Hello world

**Use case:** the first workflow to read and copy: it takes a text from the caller (or a default), turns it into a digit, branches on even or odd and prints a greeting.

```
API_TRIGGER ─► MAPPER ─► MAPPER ─► BRANCH ─even─► MAPPER ─┐
 (payload)     default   text →     even-or-odd └─odd──► MAPPER ─┴─► CONSOLE_TERMINAL
               text      digit
```

| Node | Plugin | What it does |
|---|---|---|
| `input` | `API_TRIGGER` | starts the run with the `payload` the caller sends (here `{"text": "..."}`) |
| `default-text` | `MAPPER` | uses `payload.text`, or `Yukta` when it is missing or empty |
| `to-digit` | `MAPPER` | `digit = text.length() % 10`, and keeps `text` (`dropOriginal: false`) |
| `even-or-odd` | `BRANCH` | sends the message down port `even` or `odd` (`defaultPort`) |
| `say-even`, `say-odd` | `MAPPER` | build the greeting for each branch |
| `print` | `CONSOLE_TERMINAL` | ends the run; the greeting shows in the run's logs |

**Trigger** (any HTTP client works; add `-H "X-API-Key: $YUKTA_API_KEY"` when the server has API keys)
```bash
# 1. load the workflow once
curl -X POST localhost:9858/api/sessions -H 'Content-Type: application/json' -d @session.json

# 2. run it with a text of your own ...
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-01-hello","workflowId":"hello","payload":{"text":"Grace"}}'

# ... or with no payload, to see the default
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-01-hello","workflowId":"hello"}'
```
The answer holds an `executionId`. Read the result with `GET /api/executions/<executionId>` and the greeting with
`GET /api/sessions/example-01-hello/executions/<executionId>/logs`, or open the run in the UI.

**Run:** `./examples/1-standalone/01-hello-world/run.sh` (needs the server, `curl` and `jq`; no plugin allowlist)

**Expected:** status `SUCCESS` and these greetings in the logs:

| Payload | Greeting |
|---|---|
| none | `Hello, Yukta! Digit 5 is odd.` |
| `{"text":"Grace"}` (`odd.json`) | `Hello, Grace! Digit 5 is odd.` |
| `{"text":"Lovelace"}` (`even.json`) | `Hello, Lovelace! Digit 8 is even.` |

**Build from this**
- *In the UI:* Sessions → New session → this template → create, then open the workflow and press Run. Type `{"text": "your text"}` as the payload.
- *Change the mapping:* edit the SpEL in `to-digit` (for example `payload.text.length()` without `% 10`) and branch on something else in `even-or-odd`, such as `payload.digit > 4` with ports `big` and `small`.
- *Add a branch:* add a case to `cases`, give its port an edge with `sourcePort`, and add the node that handles it. A case whose port has no edge is rejected.
- *Do real work:* replace a `say-…` node with a `PROCESS_EXECUTOR`, `HTTP_REQUEST` or `LLM_CALL` node. Those plugins are off until the server names them (see `examples/README.md`).

**Notes:** every workflow needs one trigger and one terminal. A missing payload field must be read as `payload['text']`, which gives `null`; `payload.text` fails when the field is absent. Each node logs its output, so the run logs show how the message changes step by step.
