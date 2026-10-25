#!/usr/bin/env bash
# Offline test of the triage evaluation: a stub System One server that always answers "average", and the synthetic fixtures.
# Checks the plumbing and the arithmetic of eval.py; it says nothing about a real model.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL $*" >&2; exit 1; }
dir="$(mktemp -d)"
trap 'kill "${pid:-0}" 2>/dev/null || true; rm -rf "$dir"' EXIT
cat >"$dir/stub.py" <<'PY'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        crit = body["questions"]["q"]["criteria"]
        probs = {k: (0.7 if k == "average" else 0.05) for k in crit}
        out = json.dumps({"answers": {"q": {"choice": "average", "probabilities": probs}}}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
print(s.server_address[1], flush=True)
s.serve_forever()
PY
python3 -I "$dir/stub.py" >"$dir/port" &
pid=$!
for _ in $(seq 1 50); do [[ -s "$dir/port" ]] && break; sleep 0.1; done
export OLLAMA_URL="http://127.0.0.1:$(cat "$dir/port")"
out="$(python3 -I "$here/eval.py" --cases "$here/fixtures/synthetic-b.json" --misses)"
grep -q '^14 labelled case' <<<"$out" || fail "case count: $out"
# the stub always says average: 2 of the 14 synthetic-b cases are average, 4 are within one level of it (moderate and hard: 2 each)
grep -qE '^heal.json +2/14 +6/14 ' <<<"$out" || fail "exact and within one: $out"
grep -q 'policy-exact' <<<"$out" && grep -qE '^heal.json .* 2/14 +[0-9.]+s/call, 0 failed' <<<"$out" || fail "policy: $out"
grep -q 'miss .*h-license .*trivial *got average' <<<"$out" || fail "misses are not listed: $out"
# labels by id and the split
printf '{"h-license": {"level": "trivial", "set": "dev"}, "h-404": {"level": "moderate", "set": "test"}}' >"$dir/labels.json"
out="$(python3 -I "$here/eval.py" --cases "$here/fixtures/synthetic-b.json" --labels "$dir/labels.json" --split test)"
grep -q '^1 labelled case' <<<"$out" || fail "split: $out"
# a variant file: short state, other descriptions
printf '{"question": "q", "state": "short", "levels": {"trivial": "t", "average": "a"}}' >"$dir/v.json"
python3 -I "$here/eval.py" --cases "$here/fixtures/synthetic-b.json" --variant "$dir/v.json" | grep -q '^v.json' || fail "variant file"
echo "ok   triage eval"
