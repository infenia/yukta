#!/usr/bin/env python3 -I
"""Evaluate the triage prompt of heal.json against labelled failures (README.md in this directory).

  eval.py [--cases data/cases.json --labels labels.json] [--variant heal.json ...] [--split dev|test] [--min-confidence low|medium|high] [--model M] [--misses] [--json OUT]

A case is {id, stage, short, long}; a label is {level, basis, by, confidence, set} (`set` is dev or test: tune on dev, report on test) or {exclude: why}.
Fixtures with the labels inside the case (`want`) work too: --cases fixtures/synthetic-b.json.
A variant is a heal.json, or a JSON file with {question, levels: {name: description}, state: "short" | "brief", caps, from7, collapse}:
state "short" (the default) is what heal's brief gives the model, the problem lines; "brief" adds the changed files and the end of the log
(caps: how many characters of each). A variant with other levels than the seven of labels.json says how the labels map onto its own
(from7: label to level) and onto the 3 common classes easy, average and hard (collapse: its level to class), which every variant is also
scored on. Prints, per variant, the exact match, the match within one level (a miss by one level costs one extra attempt), misses by two
levels or more in each direction, what the policy would do (a probability under minProbability falls back to the default level), the
match on the 3 classes and the time per call. OLLAMA_URL picks the server (default http://localhost:11434).
"""
import argparse, json, os, sys, time, urllib.request

here = os.path.dirname(os.path.abspath(__file__))
LEVELS = ["trivial", "easy", "simple", "moderate", "average", "hard", "veryhard"]
ORDER = ["low", "medium", "high"]
# the 3 classes every variant is also scored on, so variants with other levels compare: easy (trivial..simple), average (moderate, average), hard
COLLAPSE7 = {"trivial": "easy", "easy": "easy", "simple": "easy", "moderate": "average", "average": "average", "hard": "hard", "veryhard": "hard"}


def collapse_of(levels):
    """The 3 common classes of a variant's levels: the seven names as they are, else a variant with three levels is its own."""
    if all(k in COLLAPSE7 for k in levels):
        return COLLAPSE7
    return {k: k for k in levels} if len(levels) == 3 else {}


def load_variant(path, heal):
    d = json.load(open(path))
    t = d.get("triage", d)
    levels = {k: (v["describe"] if isinstance(v, dict) else v) for k, v in t["levels"].items()}
    # a variant with other levels says how the seven labels map onto its own (from7) and onto the 3 common classes (collapse)
    return {"name": os.path.basename(path), "question": t.get("question", "How hard is it to fix this failed check of a Java build?"),
            "levels": levels, "state": d.get("state", "short"), "model": t.get("model", heal["model"]), "caps": d.get("caps"),
            "from7": d.get("from7") or t.get("from7"), "collapse": d.get("collapse") or collapse_of(levels),
            "floor": t.get("minProbability", heal["minProbability"]), "default": t.get("default", heal["default"])}


# What heal's `brief` keeps (scripts/yukta-heal.sh, keep them equal): the problem lines, 1000 characters. A variant file may ask for more
# ("state": "brief", caps: the problem lines, the changed files, the END of the log). System One reads about 50 tokens a second on a modest
# machine and Ollama cuts a call off at 60 seconds, so every 1000 characters cost about 4 seconds.
CAPS = {"short": 1000, "changed": 600, "total": 5000}


def brief_state(c, caps=None):
    caps = caps or CAPS
    short = c["short"][:caps["short"]]
    changed, _, log = c["long"].partition("\nlog:\n")
    top = short + "\n---\n" + changed[:caps["changed"]] + "\nlog (the end):\n"
    return top + log[-max(0, caps["total"] - len(top) - 1):]


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
    print(f"{'variant':26} exact  within1  under2  over2  policy-exact  3-class  latency")
    out = {}
    for path in a.variant or [os.path.join(here, "..", "..", "heal.json")]:
        v = load_variant(path, heal)
        model = a.model or v["model"]
        ex = w1 = un = ov = pol = errors = c3 = 0
        order = list(v["levels"])
        res, secs = [], []
        for c, want in rows:
            state = c["short"][:(v["caps"] or CAPS)["short"]] if v["state"] == "short" else brief_state(c, v["caps"])
            want7 = want
            want = (v["from7"] or {}).get(want7, want7)
            if want not in order:  # a variant with fewer levels and no mapping: the nearest of its levels
                want = min(order, key=lambda l: abs(LEVELS.index(l) - LEVELS.index(want7)) if l in LEVELS else 99)
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
            i, j = order.index(ch), order.index(want)
            ex += i == j; w1 += abs(i - j) <= 1; un += j - i >= 2; ov += i - j >= 2
            pick = ch if p >= v["floor"] else v["default"]
            pol += order.index(pick) == j
            c3 += v["collapse"].get(ch) == COLLAPSE7[want7]
            res.append({"id": c["id"], "stage": c.get("stage"), "want": want, "got": ch, "p": round(p, 2), "policy": pick})
        n = len(rows)
        print(f"{v['name']:26} {ex:3}/{n}  {w1:4}/{n}  {un:6}  {ov:5}  {pol:7}/{n}  {c3:5}/{n}   {sum(secs) / len(secs):5.1f}s/call, {errors} failed", flush=True)
        out[v["name"]] = res
        if a.misses:
            for r in res:
                if r["got"] != r["want"]:
                    print(f"   miss {r['id']:>12} {str(r['stage']):10} want {r['want']:9} got {r['got']:9} p={r['p']}")
    if a.json:
        json.dump(out, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
