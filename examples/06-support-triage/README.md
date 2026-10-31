# Support ticket triage

## The pain point

Support teams answer the same few questions all day (password resets, "where is my order")
while the tickets that cost money, such as billing disputes and angry customers, wait in the
same queue. Customers also send the same message twice, and nobody can say afterwards what
happened to a given ticket. This workflow answers the routine tickets, puts the risky ones in
front of a person, drops repeats and writes one outcome line per ticket.

## The graph

`intake` (started once per ticket):

```
API_TRIGGER ─► VALIDATE ─► MAPPER ─► KV_STORE ─► BRANCH
                 │        fingerprint  incr       ├─ fresh  ─► SUB_WORKFLOW "handle" ─► end
                 │                                └─ repeat ─► MAPPER ─┐
                 └─ invalid ─► MAPPER ───────────────────────────────┴─► FILE_WRITE ─► end
```

`handle` (one new ticket):

```
API_TRIGGER ─► MAPPER lower-text ─► MAPPER classify ─► BRANCH route
                                                        │
        auto (login, order) ◄───────────────────────────┤
          │                                             └─ human (billing, angry, other)
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
| `check-fields` (VALIDATE) | Rejects a ticket with a missing or wrong-typed field (`invalid` port) |
| `fingerprint` (MAPPER) | Builds the repeat key from customer and subject, lower case |
| `seen-before` (KV_STORE `incr`) | Counts tickets per key. The result is 1 for a new ticket, 2 or more for a repeat |
| `is-repeat` (BRANCH) | `fresh` goes on, `repeat` is only logged |
| `handle-ticket` (SUB_WORKFLOW) | Runs the `handle` workflow with the ticket fields from `${start.*}` |
| `lower-text`, `classify` (MAPPER) | Keyword rules give a category: `billing`, `angry`, `login`, `order`, `other`. First match wins, billing before angry |
| `route` (BRANCH) | `login` and `order` go to `auto`; everything else goes to `human` |
| `draft-reply` (MAPPER) | Stand-in for a model: fills a canned reply. With no `orderId` the reply holds `UNKNOWN` |
| `check-reply` (BRANCH) | Quality gate: a reply holding `UNKNOWN` is `unfit` and goes to a person |
| `mark-auto`, `mark-human` (MAPPER) | Set `outcome` and the target `queue` |
| `fan-out` (RECIPIENT_LIST) | Copies an escalation to `slack`, `email` and `record` |
| `notify-slack`, `notify-email` (MAPPER) | Build the message text. Stand-ins for a real notification (see below) |
| `format-outcome`, `write-outcome` (MAPPER, FILE_WRITE) | Append one line per ticket to `outcomes.log` |

## Make it yours

- **Categories and keywords:** edit the `classify` expression. Add a category by adding a
  branch to the nested `? :` and, if it can be answered automatically, a key in `route`'s `cases`.
- **LLM swap:** replace `lower-text` and `classify` with a `CLASSIFY` node (`labels`,
  `routeByLabel`) and replace `draft-reply` with an `LLM_CALL` that writes `payload.reply`.
  `check-reply` stays: it is the guard that keeps a bad draft away from the customer. Add
  a `GATE` node there if you want a model to judge the draft instead of a rule.
- **Repeat detection:** the exact key is customer plus subject. For "means the same" matching
  use a `DEDUPE` node instead of `fingerprint`, `seen-before` and `is-repeat`. The counters
  live in memory and never expire; add the date to the key for a daily window.
- **Notifications:** `notify-slack` and `notify-email` only build the text. Put an
  `HTTP_REQUEST` (Slack incoming webhook, mail API) after them, or end the branch with
  `NOTIFY_TERMINAL`. Add or remove channels in `recipients` and the matching edges.
- **Sending the reply and the log:** `FILE_WRITE` stands in for the help desk. Replace it with
  `HTTP_REQUEST` calls to your ticket system. Change `baseDir` to a folder the server may write to.
- **Reasons:** the `queue` expression decides who gets which ticket.

## Trigger

```bash
curl -X POST localhost:9858/api/workflow/start -H 'Content-Type: application/json' \
  -d '{"sessionId":"example-06-support-triage","workflowId":"intake","payload":{"ticketId":"T-103","customer":"di@example.com","subject":"Charged twice","body":"My card was charged twice. I want a refund."}}'
```

Sample tickets are in `tickets/`.

## Run

```bash
./examples/06-support-triage/run.sh
```

It sends seven tickets, then the billing ticket again, and checks the outcome log. The script
needs `FILE_WRITE` to be allowed to write under `/tmp/yukta-examples`.

## Expected result

`/tmp/yukta-examples/support-triage/outcomes.log` holds one line per ticket:

```
ticket=T-100 category=login outcome=auto_replied queue=none
ticket=T-101 category=order outcome=auto_replied queue=none
ticket=T-102 category=order outcome=escalated queue=general
ticket=T-103 category=billing outcome=escalated queue=billing-disputes
ticket=T-104 category=angry outcome=escalated queue=senior-support
ticket=T-105 category=other outcome=escalated queue=general
DUPLICATE seen=2 fingerprint=di+...@example.com|charged twice
REJECTED invalid ticket (missing or wrong-typed fields)
```

The script prints `PASS 06-support-triage` and exits 0.
