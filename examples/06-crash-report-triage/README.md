# Crash report triage

## The pain point

The same crash arrives hundreds of times a day. Known flaky errors and bugs that already have a
ticket bury the ones that matter: a new crash, a regression after a release, an out-of-memory
error. Nobody can say afterwards what happened to a given report. This workflow fingerprints each
report, drops repeats, links known issues to their existing ticket, escalates the rest to the
owning team and writes one outcome line per report.

## The graph

`intake` (started once per crash report):

```
API_TRIGGER ─► VALIDATE ─► MAPPER ─► KV_STORE ─► BRANCH
                 │        fingerprint  incr       ├─ fresh  ─► SUB_WORKFLOW "handle" ─► end
                 │                                └─ repeat ─► MAPPER ─┐
                 └─ invalid ─► MAPPER ───────────────────────────────┴─► FILE_WRITE ─► end
```

`handle` (one new fingerprint):

```
API_TRIGGER ─► MAPPER lower-text ─► MAPPER classify ─► BRANCH route
                                                        │
        auto (timeout, npe) ◄───────────────────────────┤
          │                                             └─ human (regression, critical, other)
          ▼                                                       │
    MAPPER draft-reply                                            │
          │                                                       │
    BRANCH check-reply ─ unfit ───────────────────────────────────┤
          │ ok                                                    ▼
          ▼                                                MAPPER mark-human
    MAPPER mark-auto                                              │
          │                                              RECIPIENT_LIST fan-out
          │                                                ├─ slack ─► MAPPER ─► end
          │                                                ├─ email ─► MAPPER ─► end
          │                                                └─ record ─┐
          └──────────────────────────────────────► MAPPER format-outcome
                                                          │
                                                   FILE_WRITE ─► end
```

## What each node does

| Node | Does |
|---|---|
| `check-fields` (VALIDATE) | Rejects a report with a missing or wrong-typed field (`invalid` port) |
| `fingerprint` (MAPPER) | Builds the repeat key from exception type and top stack frame, lower case |
| `seen-before` (KV_STORE `incr`) | Counts reports per fingerprint. The result is 1 for a new one, 2 or more for a repeat |
| `is-repeat` (BRANCH) | `fresh` goes on, `repeat` is only logged |
| `handle-report` (SUB_WORKFLOW) | Runs the `handle` workflow with the report fields from `${start.*}` |
| `lower-text`, `classify` (MAPPER) | Keyword rules on exception and message give a category: `regression`, `critical`, `timeout`, `npe`, `other`. First match wins, regression before critical |
| `route` (BRANCH) | `timeout` and `npe` go to `auto`; everything else goes to `human` |
| `draft-reply` (MAPPER) | Stand-in for a lookup: the link note for the known issue. A null-pointer report with no `knownTicket` gets `UNKNOWN` |
| `check-reply` (BRANCH) | Quality gate: a note holding `UNKNOWN` is `unfit` and goes to a person (an untracked NPE is new) |
| `mark-auto`, `mark-human` (MAPPER) | Set `outcome` (`auto_linked` or `escalated`) and the owning `queue` |
| `fan-out` (RECIPIENT_LIST) | Copies an escalation to `slack`, `email` and `record` |
| `notify-slack`, `notify-email` (MAPPER) | Build the message text. Stand-ins for a real notification (see below) |
| `format-outcome`, `write-outcome` (MAPPER, FILE_WRITE) | Append one line per report to `outcomes.log` |

## Make it yours

- **Categories and keywords:** edit the `classify` expression. Add a category by adding a
  branch to the nested `? :` and, if it can be linked automatically, a key in `route`'s `cases`.
- **LLM swap:** replace `lower-text` and `classify` with a `CLASSIFY` node (`labels`,
  `routeByLabel`) fed the stack trace, so a model groups look-alike crashes. Replace
  `draft-reply` with an `LLM_CALL` that finds the matching ticket. `check-reply` stays: it is
  the guard that keeps a bad match from hiding a new crash. Add a `GATE` node there if you want
  a model to judge the match instead of a rule.
- **Repeat detection:** the exact key is exception plus top frame. For "means the same" matching
  use a `DEDUPE` node instead of `fingerprint`, `seen-before` and `is-repeat`. The counters
  live in memory and never expire; add the release version or the date to the key to count
  per release or per day.
- **Notifications:** `notify-slack` and `notify-email` only build the text. Put an
  `HTTP_REQUEST` (Slack incoming webhook, mail API) after them, or end the branch with
  `NOTIFY_TERMINAL`. Add or remove channels in `recipients` and the matching edges.
- **Ticket system and log:** `FILE_WRITE` stands in for your issue tracker. Replace it with
  `HTTP_REQUEST` calls to Jira, Linear or GitHub Issues. Change `baseDir` to a folder the server may write to.
- **Owners:** the `queue` expression decides which team gets which crash. Map `service` to a team there.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-crash-report-triage","workflowId":"intake","payload":{"reportId":"R-103","service":"search","exception":"java.lang.OutOfMemoryError","frame":"IndexWriter.addDocument(IndexWriter.java:402)","message":"Java heap space","version":"4.1.0"}}'
```

Sample reports are in `reports/`.

## Run

```bash
./examples/06-crash-report-triage/run.sh
```

It sends seven reports, then the out-of-memory report again, and checks the outcome log. The
script needs `FILE_WRITE` to be allowed to write under `/tmp/yukta-examples`.

## Expected result

`/tmp/yukta-examples/crash-report-triage/outcomes.log` holds one line per report:

```
report=R-100 category=timeout outcome=auto_linked queue=none
report=R-101 category=npe outcome=auto_linked queue=none
report=R-102 category=npe outcome=escalated queue=triage
report=R-103 category=critical outcome=escalated queue=sre-oncall
report=R-104 category=regression outcome=escalated queue=platform-oncall
report=R-105 category=other outcome=escalated queue=triage
DUPLICATE seen=2 fingerprint=java.lang.outofmemoryerror|indexwriter.adddocument(indexwriter.java:402)#run...
REJECTED invalid report (missing or wrong-typed fields)
```

The script prints `PASS 06-crash-report-triage` and exits 0.
