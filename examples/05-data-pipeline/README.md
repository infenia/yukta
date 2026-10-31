# 05 — Batch data pipeline

**Use case:** a small ETL step. A batch of records is extracted, split into one message per record, filtered, reshaped and written out.

```
CONSTANT_SOURCE (records) ──► SPLITTER ──► FILTER (active) ──► MAPPER (PROJECTION) ──► CONSOLE_TERMINAL
```

In production, swap `CONSTANT_SOURCE` for a source that reads your data (an HTTP call, a file, a database query) and the terminal for a real sink.

**Trigger**
```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-05-data-pipeline","workflowId":"etl"}'
```

**Run:** `./examples/05-data-pipeline/run.sh`

**Expected:** status `SUCCESS`; the log shows `user:Ada` and `user:Linus`. `Grace` (inactive) is dropped by the filter.
