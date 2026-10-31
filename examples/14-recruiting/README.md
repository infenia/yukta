# Screening a batch of job applications

## The pain point

A role gets dozens of applications. Reading them all is slow, scores kept in a head are not
consistent, and anything the reviewer sees (name, age, nationality, photo) can bias the decision.
This workflow checks each application, removes personal and protected details before a person looks
at it, scores it with the same written weights for everyone, and leaves the decision to a recruiter.

## The graph

```
batch
API_TRIGGER ─► VALIDATE ─ok─► SPLITTER ─► screen (SUB_WORKFLOW, one at a time) ─► pick ─► AGGREGATE ─► summarise ─► write-summary ─► end
                  └invalid─► bad-batch ──────────────────────────────────────────────────────────────────────────────►┘

screen (one application)
API_TRIGGER ─► VALIDATE ─ok─► CONTENT_FILTER (redact) ─► score ─► band (BRANCH)
                  │                                               ├─ strong ─┐
                  │                                               ├─ maybe ──┴─► recruiter (WAIT_FOR_SIGNAL "decision") ─► decide (BRANCH)
                  │                                               └─ weak ─► auto-decline ─┐          ├─ invite  ─► stamp ─► write-invite ──┐
                  └invalid─► stamp-invalid ───────────────────────────────────────────────┐ │          ├─ pool    ─► stamp ─► write-pool ────┤
                                                                                           │ └► decline ◄─ decline ─► stamp ─► write-decline ─┤
                                                                                           └──────────────────────────────────────► result ◄──┘ ─► done
```

## What each node does

| Node | Type | What it does |
|---|---|---|
| `split` | SPLITTER | One message per applicant from `payload.applicants` |
| `screen` | SUB_WORKFLOW | Runs the per-application workflow, one applicant at a time |
| `validate` | VALIDATE | Needs `id`, `years` (number) and `skills` (list); anything else goes to `stamp-invalid` |
| `redact` | CONTENT_FILTER | `EXCLUDE` removes name, email, phone, photo, gender, age, date of birth, nationality, marital status, address |
| `score` | MAPPER | Years of experience x4 (capped at 10 years), +15 per required skill (`java`, `sql`, `spring`), +7 per bonus skill (`kubernetes`, `docker`) |
| `band` | BRANCH | 75 or more `strong`, 40 to 74 `maybe`, below 40 `weak` |
| `recruiter` | WAIT_FOR_SIGNAL | Waits for the signal `decision`; a timeout after 15 minutes files the person in the talent pool |
| `decide` | BRANCH | `invite`, `pool` or `decline` from the signal's decision |
| `stamp-*`, `write-*` | MAPPER, FILE_WRITE | Append a line to `invitations.txt`, `talent-pool.txt` or `declines.txt` (stand-ins for an ATS update and an email) |
| `collect` | AGGREGATE | Gathers the results of the batch (up to 100, or when the batch ends) |
| `summarise` | MAPPER | Writes `SUMMARY: 6 applications, invite=2 pool=1 decline=2 invalid=1` to `summary.txt` |

The recruiter sees only `id`, `years`, `skills`, the point breakdown and the score, because the
filter runs before scoring. Letters and invitations use the `id`; merge the contact details back
from your applicant system. The trigger, `validate` and `split` still print the full application in
the node logs, so keep logs access restricted or filter at intake if that matters.

## Make it yours

- **Weights and skills:** edit the `score` node (the three expressions that repeat the formula) and
  the thresholds in `band`. Skills are matched as lower-case text, so normalise them first.
- **Protected attributes:** edit the `paths` list in `redact`. For an allow-list instead, use
  `mode: INCLUDE` and list only what the reviewer may see.
- **Who decides:** `weak` applications are declined by the fixed rule. If your policy needs a person
  for every decline, point the `weak` edge of `band` at `recruiter`.
- **Outcomes:** swap the `write-*` nodes for `HTTP_REQUEST` (ATS or email API), and add more
  decision names to `decide`.
- **LLM:** none is needed. If you want a model to summarise a CV for the recruiter, add an `LLM_CALL`
  after `redact` (so it never sees the removed fields) and let it add a `summary` field; keep the
  weights and the human decision as they are.
- **Batch size:** `size` in `collect` (100) is the most results per summary.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-14-recruiting\",\"workflowId\":\"batch\",\"payload\":$(cat examples/14-recruiting/batch.json)}"

curl localhost:9858/api/sessions/example-14-recruiting/approvals      # what waits for the recruiter
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/recruiter/signal?name=decision&decision=invite"
```

Decisions: `invite`, `pool`, `decline`. The execution id in the approvals list is the one of the
application's own run, not of the batch.

## Run

Needs no `PROCESS_EXECUTOR` allow-list; the nodes used are VALIDATE, CONTENT_FILTER, MAPPER, BRANCH,
SPLITTER, AGGREGATE, SUB_WORKFLOW, WAIT_FOR_SIGNAL and FILE_WRITE.

```bash
./examples/14-recruiting/run.sh
```

The script plays the recruiter: invite, pool, decline, invite for the four applications that reach
the recruiter. It also checks that the recruiter's steps never printed a protected field, name or email.

## Expected result

Last line `PASS 14-recruiting: ...`. Output files are written to `/tmp/yukta-examples/recruiting`
(change `baseDir` in `session.json`; the script deletes the folder when it ends).
