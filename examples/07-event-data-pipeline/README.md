# Nightly event load

## The pain point

Product events are exported nightly as a CSV and something has to load them into the
warehouse. One malformed event should not stop the load, an event replayed by the client SDK
should not be counted twice, and nobody wants to find out a week later that half the file was
garbage. This workflow loads the good events in batches, sets the bad ones aside in a quarantine
file, and fails the run in a way that cannot be missed when too many events are bad.

## The graph

`nightly` (the timer):

```
SCHEDULE_TRIGGER ─► SUB_WORKFLOW "pipeline" ─┬─ success ─► end
 cron 0 0 2 * * *                            └─ failure ─► MAPPER ─► FILE_WRITE alerts.log ─► end
```

`pipeline` (the work, also startable on its own):

```
API_TRIGGER ─► FILE_READ ─► SPLITTER ─► FILTER ─► MAPPER ─► KV_STORE ─► BRANCH is-repeat
  {path}       whole file   one line    header    row-key   incr         ├─ repeat ─► MAPPER mark-repeat ─────────┐
                            per row     blanks                           └─ fresh ─► MAPPER parse-row             │
                                                                                         │                        │
                                                                                   VALIDATE check-row             │
                                                                          ┌─ ok ──────────┴─ invalid ─┐           │
                                                                          ▼                           ▼           │
                                                              RECIPIENT_LIST good-fan-out    MAPPER ─► FILE_WRITE │
                                                                  ├─ load ─► AGGREGATE (3)    quarantine.csv      │
                                                                  │          ─► MAPPER ─► FILE_WRITE batches      │
                                                                  └─ count ─► MAPPER mark-good ─┐   mark-bad ─────┤
                                                                                                └──► AGGREGATE tally ◄┘
                                                                                                       │
                                                  MAPPER summarise ─► FILE_WRITE summary.log ─► BRANCH fail-if-too-many-bad-rows
                                                                                                  └─ within-limit ─► end
```

## What each node does

| Node | Does |
|---|---|
| `at-2am` (SCHEDULE_TRIGGER) | Fires at 02:00 every day. `timeoutSeconds` is above 24 hours so the node stays alive between ticks |
| `run-pipeline` (SUB_WORKFLOW) | Runs `pipeline` for the file `inbox/events.csv`. With `routeByOutcome` a failed run goes to `failure` instead of ending the schedule |
| `format-alert`, `write-alert` | Append `ALERT nightly event load failed: FAILURE` to `out/alerts.log` |
| `read-file` (FILE_READ) | Reads the event file named in `payload.path`, inside the sandbox folder. A missing file fails the run |
| `split-lines`, `skip-header-and-blanks` | One message per line; the header and empty lines are dropped |
| `row-key`, `seen-row`, `is-repeat` | `KV_STORE` `incr` counts each exact line. A count above 1 means the event was replayed, in this file or in an earlier night |
| `parse-row` (MAPPER) | Splits the line into `event_id`, `user_id`, `event_type`, `ts`. A cell that fails its pattern (id characters, one of `page_view|click|purchase|signup`, ISO timestamp) becomes null |
| `check-row` (VALIDATE) | A null field sends the row to `invalid` |
| `good-fan-out` (RECIPIENT_LIST) | Sends a good row to the loader and to the counter |
| `batch`, `format-batch`, `write-batch` | Collect 3 good events, write them as one line of `out/loaded-batches.txt` |
| `format-quarantine`, `write-quarantine` | Append the original line of a bad row to `out/quarantine.csv` |
| `mark-*`, `tally` (AGGREGATE) | Each row leaves a one-word status; `tally` waits for the end of the input and emits them all |
| `summarise`, `write-summary` | Count rows by status, compute the bad percentage, append `rows=… good=… bad=… repeat=…` to `out/summary.log` |
| `fail-if-too-many-bad-rows` (BRANCH) | Has a case only for 25 percent or less and no default port, so any other value fails this node and the run |

## Make it yours

- **Schedule:** change `cron` (six fields: second, minute, hour, day, month, weekday) and `zone`.
  Keep `timeoutSeconds` larger than the longest gap.
- **File and sandbox:** change `baseDir` (all file nodes) and the `path` in `run-pipeline`. The
  path is fixed text, so have the export job write a stable name such as `events.csv`.
- **Columns and rules:** edit `parse-row` (one expression per column, null when the cell is
  not acceptable) and the `required` list of `check-row`. Add a column to `format-batch`; allowed event types are the regex in `parse-row`.
- **Batch size:** `batch.size`. A partial last batch is written when the input ends.
- **Threshold:** the `25` in `fail-if-too-many-bad-rows`.
- **Real load:** `write-batch` only appends a line per batch. Replace it with `SQL_QUERY` (or a warehouse bulk-load call)
  (needs a JDBC driver in your build, for example PostgreSQL; none ships for file databases)
  or an `HTTP_REQUEST` to a bulk API.
- **Alert:** `write-alert` stands in for a page or chat message to the data on-call; put `HTTP_REQUEST` or
  `NOTIFY_TERMINAL` there.
- **Replay check:** it matches exact lines and the counters live in memory until the server
  restarts. For a real load keep the check in a database or use the `KV_STORE` PostgreSQL option.
- **LLM:** none needed. If events carry free-text fields (for example a user-agent string or a
  page title), swap the `parse-row` MAPPER for an `LLM_CALL`, or add a `CLASSIFY` node after it, to
  normalise or label them.

## Trigger

The schedule starts when you start `nightly` once:

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-07-event-data-pipeline","workflowId":"nightly"}'
```

To run the work right now without waiting for 02:00:

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-07-event-data-pipeline","workflowId":"pipeline","payload":{"path":"inbox/events.csv"}}'
```

## Run

```bash
./examples/07-event-data-pipeline/run.sh
```

The script copies the sample files in `data/` into `/tmp/yukta-examples/nightly/inbox`, runs
`pipeline` for a good file, a mostly bad file and a missing file, then applies a copy of the
session with the schedule shortened to one tick after 2 seconds and runs `nightly` for a good
and a bad night. It re-applies the real session at the end.

## Expected result

For `data/events-ok.csv` (8 rows: 6 good, 1 bad event type, 1 replayed event):

```
out/summary.log          rows=8 good=6 bad=1 repeat=1
out/quarantine.csv       E4,u-dee,teleport,2026-10-30T01:02:00Z
out/loaded-batches.txt   batch of 3: E1|u-ana|page_view|2026-10-30T01:00:00Z, E2|u-bo|click|…, E3|u-cy|purchase|…
                         batch of 3: E5|…, E6|…, E7|…
```

The run ends `SUCCESS`. For `data/events-bad.csv` the summary reads `rows=6 good=1 bad=5`, the
run ends `FAILURE` at `fail-if-too-many-bad-rows`, and in the `nightly` path `out/alerts.log`
gets the alert line. The script prints `PASS 07-event-data-pipeline` and exits 0.
