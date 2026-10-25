#!/usr/bin/env python3 -I
"""Evaluate the triage prompt of heal.json against labelled failures (README.md in this directory).

  eval.py [--cases data/cases.json --labels labels.json] [--variant heal.json ...] [--split dev|test] [--min-confidence low|medium|high] [--model M] [--misses] [--json OUT]

A case is {id, stage, short, long}; a label is {level, basis, by, confidence, set} (`set` is dev or test: tune on dev, report on test) or {exclude: why}.
Fixtures with the labels inside the case (`want`) work too: --cases fixtures/synthetic-b.json.
A variant is a heal.json, or a JSON file with {question, levels: {name: description}, state: "short" | "long"} (state defaults to long,
what heal's brief gives the model). Prints, per variant, the exact match, the match within one level (a miss by one level costs one
extra attempt), misses by two levels or more in each direction, and what the policy of heal.json would do (a probability under
minProbability falls back to the default level). OLLAMA_URL picks the server (default http://localhost:11434).
"""
import argparse, json, os, sys, time, urllib.request

here = os.path.dirname(os.path.abspath(__file__))
LEVELS = ["trivial", "easy", "simple", "moderate", "average", "hard", "veryhard"]
ORDER = ["low", "medium", "high"]


def load_variant(path, heal):
    d = json.load(open(path))
    t = d.get("triage", d)
    levels = {k: (v["describe"] if isinstance(v, dict) else v) for k, v in t["levels"].items()}
    return {"name": os.path.basename(path), "question": t.get("question", "How hard is it to fix this failed check of a Java build?"),
            "levels": levels, "state": d.get("state", "long"), "model": t.get("model", heal["model"]),
            "floor": t.get("minProbability", heal["minProbability"]), "default": t.get("default", heal["default"])}


# What heal's `brief` keeps (scripts/yukta-heal.sh, keep them equal): the problem lines, the changed files, and the END of the log, 5000
# characters. System One reads about 50 tokens a second on a modest machine and Ollama cuts a call off at 60 seconds.
CAPS = {"short": 1800, "changed": 600, "total": 5000}


def brief_state(c):
    short = c["short"][:CAPS["short"]]
    changed, _, log = c["long"].partition("\nlog:\n")
    top = short + "\n---\n" + changed[:CAPS["changed"]] + "\nlog (the end):\n"
    return top + log[-max(0, CAPS["total"] - len(top) - 1):]


def ask(url, model, state, question, levels):
    body = {"model": model, "state": state, "questions": {"q": {"type": "choice", "instructions": question, "criteria": levels}}}
    req = urllib.request.Request(url + "/v1/systemone", json.dumps(body).encode(), {"Content-Type": "application/json"})
    a = json.load(urllib.request.urlopen(req, timeout=600))["answers"]["q"]
    return a["choice"], max(a["probabilities"].values())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=os.path.join(here, "data", "cases.json"))
    ap.add_argument("--labels", default=os.path.join(here, "labels.json"))
    ap.add_argument("--variant", action="append")
    ap.add_argument("--split", choices=["dev", "test"])
    ap.add_argument("--min-confidence", choices=["low", "medium", "high"], help="only labels at least this sure")
    ap.add_argument("--model")
    ap.add_argument("--misses", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    heal = json.load(open(os.path.join(here, "..", "..", "heal.json")))["triage"]
    cases = json.load(open(a.cases))
    labels = json.load(open(a.labels)) if os.path.exists(a.labels) else {}
    rows = []
    for c in cases:
        lab = labels.get(c["id"], {})
        want = lab.get("level") or c.get("want")
        if lab.get("exclude") or not want or want not in LEVELS or (a.split and lab.get("set", "dev") != a.split):
            continue
        if a.min_confidence and ORDER.index(lab.get("confidence", "high")) < ORDER.index(a.min_confidence):
            continue
        rows.append((c, want))
    if not rows:
        sys.exit("no labelled case (labels.json holds the labels by case id; see README.md)")
    url = os.environ.get("OLLAMA_URL", "http://localhost:11434")
    print(f"{len(rows)} labelled case(s), model {a.model or heal['model']}")
    print(f"{'variant':26} exact  within1  under2  over2  policy-exact  latency")
    out = {}
    for path in a.variant or [os.path.join(here, "..", "..", "heal.json")]:
        v = load_variant(path, heal)
        model = a.model or v["model"]
        ex = w1 = un = ov = pol = errors = 0
        res, secs = [], []
        for c, want in rows:
            state = c["short"] if v["state"] == "short" else brief_state(c)
            t0 = time.time()
            try:
                ch, p = ask(url, model, state, v["question"], v["levels"])
            except Exception as e:  # a failed call is what heal sees too: the default level, and a miss here
                errors += 1
                ch, p = "error", 0.0
                print(f"   error {c['id']}: {e}", file=sys.stderr)
            secs.append(time.time() - t0)
            if ch == "error":
                un += 0
                res.append({"id": c["id"], "stage": c.get("stage"), "want": want, "got": "error", "p": 0, "policy": v["default"]})
                pol += v["default"] == want
                continue
            i, j = LEVELS.index(ch), LEVELS.index(want)
            ex += i == j; w1 += abs(i - j) <= 1; un += j - i >= 2; ov += i - j >= 2
            pick = ch if p >= v["floor"] else v["default"]
            pol += LEVELS.index(pick) == j
            res.append({"id": c["id"], "stage": c.get("stage"), "want": want, "got": ch, "p": round(p, 2), "policy": pick})
        n = len(rows)
        print(f"{v['name']:26} {ex:3}/{n}  {w1:4}/{n}  {un:6}  {ov:5}  {pol:7}/{n}   {sum(secs) / len(secs):5.1f}s/call, {errors} failed", flush=True)
        out[v["name"]] = res
        if a.misses:
            for r in res:
                if r["got"] != r["want"]:
                    print(f"   miss {r['id']:>12} {str(r['stage']):10} want {r['want']:9} got {r['got']:9} p={r['p']}")
    if a.json:
        json.dump(out, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
