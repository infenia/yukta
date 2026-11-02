# Triaging a batch of security scanner findings

## The pain point

A nightly scan reports hundreds of findings. Most are noise (unreachable code, internal-only services),
a few are urgent, and the scanner's own severity ignores both. Worse, secret scanners print the leaked
secret itself (an AWS key, a GitHub token, a private key), so the triage queue becomes a second place
the secret leaks to. This workflow checks each finding, removes the secret values before a person looks
at it, scores it with the same written weights every night, and leaves the decision to an engineer.

## The graph

```
batch
API_TRIGGER ─► VALIDATE ─ok─► SPLITTER ─► triage (SUB_WORKFLOW, one at a time) ─► pick ─► AGGREGATE ─► summarise ─► write-summary ─► end
                  └invalid─► bad-batch ──────────────────────────────────────────────────────────────────────────────►┘

triage (one finding)
API_TRIGGER ─► VALIDATE ─ok─► CONTENT_FILTER (redact) ─► score ─► band (BRANCH)
                  │                                               ├─ strong ─┐
                  │                                               ├─ maybe ──┴─► engineer (WAIT_FOR_SIGNAL "decision") ─► decide (BRANCH)
                  │                                               └─ weak ─► auto-suppress ─┐          ├─ fix     ─► stamp ─► write-fix ───────┐
                  └invalid─► stamp-invalid ───────────────────────────────────────────────┐ │          ├─ accept  ─► stamp ─► write-accept ────┤
                                                                                           │ └► suppress ◄─ suppress ─► stamp ─► write-suppress ─┤
                                                                                           └──────────────────────────────────────► result ◄──┘ ─► done
```

## What each node does

| Node | Type | What it does |
|---|---|---|
| `split` | SPLITTER | One message per finding from `payload.findings` |
| `triage` | SUB_WORKFLOW | Runs the per-finding workflow, one finding at a time |
| `validate` | VALIDATE | Needs `id`, `severity` and `snippet` (text); anything else goes to `stamp-invalid` |
| `redact` | CONTENT_FILTER | `EXCLUDE` removes `matchedSecret` (the AKIA key, `ghp_` token or private-key block), `rawLine` (the source line holding it), `committedBy`, `authorEmail`, `blame` |
| `score` | MAPPER | severity (critical 100, high 70, medium 40, low 10) x reachability (reachable 1.0, not 0.4) x exposure (internet-exposed 1.0, internal 0.6) |
| `band` | BRANCH | 60 or more `strong`, 30 to 59 `maybe`, below 30 `weak` |
| `engineer` | WAIT_FOR_SIGNAL | Waits for the signal `decision`; after 15 minutes without an answer the finding is filed as a fix |
| `decide` | BRANCH | `fix`, `accept-risk` or `suppress` from the signal's decision |
| `stamp-*`, `write-*` | MAPPER, FILE_WRITE | Append a line to `fixes.txt`, `accepted-risks.txt` or `suppressions.txt` (stand-ins for a ticket, a risk register entry and a scanner suppression) |
| `collect` | AGGREGATE | Gathers the results of the batch (up to 100, or when the batch ends) |
| `summarise` | MAPPER | Writes `SUMMARY: 6 findings, fix=2 accept=1 suppress=2 invalid=1` to `summary.txt` |

The engineer sees only `id`, `rule`, `severity`, `file`, the flags, `snippet`, the point breakdown and the
score, because the filter runs before scoring. The trigger, `validate` and `split` still print the full
finding, secret included, in the node logs, so keep logs access restricted or redact in the scanner
export. The leaked secret itself must still be rotated; this workflow only stops it spreading.

## Make it yours

- **Weights:** edit the `score` node (`severityPoints`, `reachFactor`, `exposureFactor` and the `score`
  expression that repeats them) and the thresholds in `band`.
- **Redaction:** `CONTENT_FILTER` removes whole fields, it does not mask text inside one. Have the
  scanner put secrets in separate fields (as `matchedSecret` here) and list them in `paths`. For an
  allow-list instead, use `mode: INCLUDE` and list only what the engineer may see.
- **Who decides:** `weak` findings are suppressed by the fixed rule. If your policy needs a person for
  every suppression, point the `weak` edge of `band` at `engineer`.
- **Outcomes:** swap the `write-*` nodes for `HTTP_REQUEST` (Jira, a risk register, the scanner's
  suppression API), and add more decision names to `decide`.
- **LLM:** none is needed. To help the engineer, swap in an `LLM_CALL` after `redact` (so it never
  sees the removed secrets) as an exploitability summariser that adds a `summary` field; keep the
  weights and the human decision as they are.
- **Batch size:** `size` in `collect` (100) is the most results per summary.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"example-14-security-findings\",\"workflowId\":\"batch\",\"payload\":$(cat examples/14-security-findings/findings.json)}"

curl localhost:9858/api/sessions/example-14-security-findings/approvals      # what waits for the engineer
curl -X POST "localhost:9858/api/executions/<executionId>/nodes/engineer/signal?name=decision&decision=fix"
```

Decisions: `fix`, `accept-risk`, `suppress`. The execution id in the approvals list is the one of the
finding's own run, not of the batch.

## Run

Needs no `PROCESS_EXECUTOR` allow-list; the nodes used are VALIDATE, CONTENT_FILTER, MAPPER, BRANCH,
SPLITTER, AGGREGATE, SUB_WORKFLOW, WAIT_FOR_SIGNAL and FILE_WRITE.

```bash
./examples/14-security-findings/run.sh
```

The script plays the engineer: fix, accept-risk, suppress, fix for the four findings that reach a
person (F-101, F-102, F-103, F-106). F-104 scores 2 and is suppressed automatically, F-105 is invalid
(no `snippet`). It also checks that the engineer's steps never printed a secret, key block or author email.

## Expected result

Last line `PASS 14-security-findings: ...`. Output files are written to `/tmp/yukta-examples/security-findings`
(change `baseDir` in `session.json`; the script deletes the folder when it ends).
