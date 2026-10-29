#!/usr/bin/env python3 -I
"""A stand-in for Ollama's System One endpoint (and /api/version, /api/tags; the model "stale" and "missing" answer as Ollama does), for the online tests of heal (test-self-heal.sh --online).

  fake-ollama.py PORT_FILE

Listens on a free port on 127.0.0.1 and writes it to PORT_FILE. POST /v1/systemone answers a choice question: the level named in
the state after "difficulty:" (for example a failing check whose output says "difficulty: hard"), else "average" at 0.7. A state
that says "difficulty: uncertain" gets 0.1 for every level (below the confidence floor); one that says "difficulty: boom" gets an HTTP 500.
"""
import http.server, json, sys


class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        state = body.get("state", "")
        name, question = next(iter(body["questions"].items()))
        crit = list(question["criteria"])
        want = "average"
        if body["model"] == "stale":  # what Ollama says of a model in the old format
            self.send_response(500); self.end_headers(); self.wfile.write(b"'stale' was downloaded in a format that is no longer supported. To re-download, run 'ollama pull stale'"); return
        if body["model"] == "missing":
            self.send_response(404); self.end_headers(); self.wfile.write(b"model 'missing' not found"); return
        if "difficulty:" in state:
            want = state.split("difficulty:")[1].split()[0].strip(".,;")
        if want == "boom":
            self.send_response(500); self.end_headers(); self.wfile.write(b"boom"); return
        probs = {k: 0.05 for k in crit}
        if want == "uncertain":
            probs = {k: 0.1 for k in crit}
            want = crit[0]
        elif want in probs:
            probs[want] = 0.7
        else:  # a question without that label: the first one
            want = crit[0]
            probs[want] = 0.7
        out = json.dumps({"model": body["model"], "answers": {name: {"type": "choice", "choice": want, "probabilities": probs}},
                          "usage": {"input_tokens": len(state) // 4, "output_tokens": 0}}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)

    def do_GET(self):
        out = json.dumps({"version": "0.40.1"} if self.path == "/api/version" else {"models": [{"name": "fake"}]}).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)

    def log_message(self, *a):
        pass


s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_address[1]))
s.serve_forever()
