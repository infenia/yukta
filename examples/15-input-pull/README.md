# Pull responder for input requests

A script that plays an external agent or a ticket bot: it lists the input requests that wait on
the server and answers one, with `curl` and `jq`, and nothing to install on the server.

```bash
export YUKTA_API_KEY=<an OPERATOR key>
./inputs.sh list
./inputs.sh answer <executionId> <requestId> approve "looks fine"
```

`list` calls `GET /api/inputs` (open and escalated requests of every session the key may see);
`answer` calls `POST /api/executions/{executionId}/inputs/{requestId}/answer`. Whoever answers is
bound by the rules of the request: its audience, its tier, its risk (a `HIGH` request needs a person)
and the sessions of the key.

To be told instead of asking, follow `GET /api/events/stream?type=InputRequested,InputEscalated`
(server-sent events), or use the MCP tools `list_pending_inputs` and `answer_input`. To be called
when a request needs you, use a webhook tier: see
[External responders](../../docs/building/input-requests.mdx#external-responders-webhooks-and-one-time-tokens).
