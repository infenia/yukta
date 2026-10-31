#!/usr/bin/env bash
# Offline test of channel/yukta-pickup.py (no Claude, no server, no cost, about 3 s): the MCP handshake, the channel event for a POST,
# the token (403), busy (409) and pickup_done freeing the channel.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
tmp="$(mktemp -d)"
cleanup() { exec 3>&- 2>/dev/null || true; [[ -z "${pid:-}" ]] || kill "$pid" 2>/dev/null || true; rm -rf "$tmp"; }
trap cleanup EXIT
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
echo secret >"$tmp/pickup-token"
mkfifo "$tmp/in"
YUKTA_DEV_HOME="$tmp" YUKTA_PICKUP_PORT="$port" python3 -I "$here/channel/yukta-pickup.py" <"$tmp/in" >"$tmp/out" 2>"$tmp/err" &
pid=$!
exec 3>"$tmp/in"
rpc() { echo "$1" >&3; }
url="http://127.0.0.1:$port"
code() { curl -s -o "$tmp/body" -w '%{http_code}' "$@"; }
issue='{"number":7,"title":"t","url":"https://example.test/7"}'

rpc '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}'
rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}'
for _ in $(seq 50); do curl -fs "$url/status" >/dev/null 2>&1 && break; sleep 0.1; done
for _ in $(seq 30); do [[ -s "$tmp/out" ]] && break; sleep 0.1; done
init="$(head -1 "$tmp/out")"
jq -e '.result.capabilities.experimental["claude/channel"] == {}' <<<"$init" >/dev/null || fail "no claude/channel capability: $init"
jq -e '.result.protocolVersion == "2025-06-18"' <<<"$init" >/dev/null || fail "must not negotiate 2026-07-28: $init"
jq -e '.result.instructions | contains("pickup_done")' <<<"$init" >/dev/null || fail "instructions do not name pickup_done"

[[ "$(code "$url/status")" == 200 ]] && jq -e '.busy == false' "$tmp/body" >/dev/null || fail "status idle"
[[ "$(code -X POST -H 'X-Pickup-Token: wrong' -d "$issue" "$url/issue")" == 403 ]] || fail "bad token must be 403"
[[ "$(code -X POST -d "$issue" "$url/issue")" == 403 ]] || fail "no token must be 403"
[[ "$(code -X POST -H 'X-Pickup-Token: secret' -d '{"number":"x"}' "$url/issue")" == 400 ]] || fail "bad body must be 400"
[[ "$(code -X POST -H 'X-Pickup-Token: secret' -d "$issue" "$url/issue")" == 202 ]] || fail "good POST must be 202"
for _ in $(seq 30); do [[ "$(wc -l <"$tmp/out")" -ge 2 ]] && break; sleep 0.1; done
ev="$(sed -n 2p "$tmp/out")"
jq -e '.method == "notifications/claude/channel" and .params.meta.issue == "7" and .params.meta.url == "https://example.test/7" and (.params.content | contains("#7"))' <<<"$ev" >/dev/null || fail "channel event: $ev"
[[ "$(wc -l <"$tmp/out")" == 2 ]] || fail "exactly one event expected"
[[ "$(code -X POST -H 'X-Pickup-Token: secret' -d "$issue" "$url/issue")" == 409 ]] || fail "busy must be 409"
jq -e '.busy == true and .issue == 7' < <(curl -s "$url/status") >/dev/null || fail "status busy"

rpc '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pickup_done","arguments":{"issue":7,"outcome":"pr","url":"https://example.test/pr/1"}}}'
for _ in $(seq 30); do [[ "$(wc -l <"$tmp/out")" -ge 3 ]] && break; sleep 0.1; done
jq -e '.id == 2 and .result.isError == false' <<<"$(sed -n 3p "$tmp/out")" >/dev/null || fail "pickup_done answer: $(sed -n 3p "$tmp/out")"
jq -e '.busy == false' < <(curl -s "$url/status") >/dev/null || fail "pickup_done must free the channel"
jq -e '.issue == 7 and .outcome == "pr"' "$tmp/pickup.jsonl" >/dev/null || fail "pickup.jsonl"
[[ "$(code -X POST -H 'X-Pickup-Token: secret' -d "$issue" "$url/issue")" == 202 ]] || fail "free again: POST must be 202"
# a second session must not start: the port is taken
if out="$(YUKTA_DEV_HOME="$tmp" YUKTA_PICKUP_PORT="$port" "$here/yk" pickup session 2>&1)"; then fail "a second pickup session must be refused"; fi
[[ "$out" == *"already owns :$port"* ]] || fail "second session message: $out"
echo "ok: test-pickup-channel"
