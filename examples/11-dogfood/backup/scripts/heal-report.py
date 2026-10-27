#!/usr/bin/env python3 -I
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""What heal did, from its event log: where the time and the money went, and what looks wrong.

  heal-report.py report  [--events FILE] [--since 7d|DATE] [--run ID] [--project NAME] [--json]
  heal-report.py analyze [same options]      the findings only (rules over the events), exit 1 when there are any
  heal-report.py show    [same options]      the events as one line each, oldest first

The log is examples/11-dogfood/README.md "Monitoring heal": one JSON object per line, written by yukta-heal.sh and yk.
Every event has v, ts, run (the root execution id), session, project and event; the rest depends on the event:
  stage    stage, secs, exit, ok, cmd, and on a failure sig and head (a stable hash of the failure and its first lines)
  triage   stage, route (level | uncertain | error), label, p, start, model, secs (since the brief), chars, fallback, error
  fix      stage, attempt, model, effort, fixed, claimed, files, cost, usage, secs, guard, summary
  recheck  stage, secs, ok          verify  secs, ok          giveup  stage
  result   workflow, status, failed_node, healed, secs, scope, durations (seconds per stage), sha, branch
"""
import argparse, collections, datetime, json, os, statistics, sys

DEFAULT = os.path.join(os.environ.get("YUKTA_DEV_STATE_DIR", os.path.expanduser("~/.local/state/yukta-dev")), "heal-events.jsonl")
MODELS = ["haiku", "sonnet", "opus"]


def load(path):
    out = []
    try:
        for line in open(path, errors="replace"):
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if isinstance(e, dict) and e.get("event"):
                out.append(e)
    except FileNotFoundError:
        pass
    return out


def since(spec):
    if not spec:
        return None
    if spec[-1] in "dh" and spec[:-1].isdigit():
        n = int(spec[:-1]) * (86400 if spec[-1] == "d" else 3600)
        return (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=n)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return spec


def pct(xs, q):
    xs = sorted(xs)
    if not xs:
        return 0.0
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))]


def fmt_s(s):
    return f"{s:.0f}s" if s < 120 else f"{s / 60:.1f}m"


def rung(e):
    return f"{e.get('model')}/{e.get('effort')}" if e.get("effort") else str(e.get("model"))


def analyse(ev):
    runs = collections.defaultdict(list)
    for e in ev:
        runs[e.get("run", "-")].append(e)
    heals = collections.defaultdict(lambda: {"fix": [], "triage": None, "recheck": [], "giveup": False})  # (run, stage)
    for r, es in runs.items():
        for e in es:
            k = (r, e.get("stage"))
            if e["event"] == "fix" and e.get("model") != "auto":
                heals[k]["fix"].append(e)
            elif e["event"] == "triage":
                heals[k]["triage"] = e
            elif e["event"] == "recheck":
                heals[k]["recheck"].append(e)
            elif e["event"] == "giveup":
                heals[k]["giveup"] = True
    return runs, heals


def report(ev):
    runs, heals = analyse(ev)
    R = {}
    # a plain gradle run has no heal; a run's result can be written twice (by yk watch and by the PR step's yk result): the last one counts
    last = {}
    for e in ev:
        if e["event"] == "result" and e.get("workflow") != "gradle-run":
            last[e.get("run")] = e
    results = list(last.values())
    R["runs"] = {"with_events": len(runs), "results": len(results), "status": dict(collections.Counter(e.get("status") for e in results)),
                 "wall_median_s": statistics.median([e["secs"] for e in results if "secs" in e] or [0]),
                 "wall_p95_s": pct([e["secs"] for e in results if "secs" in e], 0.95)}
    # where the time goes
    tot = collections.Counter()
    for e in ev:
        k = {"stage": "stage runs", "triage": "triage", "fix": "fix attempts", "recheck": "rechecks", "verify": "verify"}.get(e["event"])
        if k == "triage":
            tot[k] += e.get("secs") or 0
        elif k:
            tot[k] += e.get("secs") or 0
    R["time"] = dict(tot)
    stages = collections.defaultdict(lambda: {"runs": 0, "failed": 0, "secs": []})
    for e in ev:
        if e["event"] == "stage":
            s = stages[e.get("stage")]
            s["runs"] += 1; s["failed"] += not e.get("ok", True); s["secs"].append(e.get("secs") or 0)
    R["stages"] = {k: {"runs": v["runs"], "failed": v["failed"], "mean_s": statistics.mean(v["secs"]), "p95_s": pct(v["secs"], .95)} for k, v in stages.items()}
    # heals
    hs = [h for h in heals.values() if h["fix"] or h["triage"] or h["giveup"]]
    solved = [h for h in hs if any(f.get("fixed") for f in h["fix"])]
    fixed_at = collections.Counter(rung(next(f for f in h["fix"] if f.get("fixed"))) for h in solved)
    R["heals"] = {"count": len(hs), "solved": len(solved), "giveups": sum(h["giveup"] for h in hs),
                  "attempts_per_heal": statistics.mean([len(h["fix"]) for h in hs] or [0]), "fixed_at": dict(fixed_at.most_common())}
    # triage
    tr = [e for e in ev if e["event"] == "triage"]
    R["triage"] = {"calls": len(tr), "routes": dict(collections.Counter(e.get("route") for e in tr)),
                   "fallbacks": dict(collections.Counter(e.get("fallback") for e in tr if e.get("fallback"))),
                   "latency_p50_s": pct([e["secs"] for e in tr if e.get("secs") is not None], .5), "latency_p95_s": pct([e["secs"] for e in tr if e.get("secs") is not None], .95),
                   "confidence_median": statistics.median([e["p"] for e in tr if e.get("p")] or [0]),
                   "start": dict(collections.Counter(e.get("start") for e in tr).most_common())}
    under = over = 0
    for h in solved:
        if h["triage"] and h["triage"].get("start") and h["triage"].get("route") == "level":
            s = h["triage"]["start"].split("/")[0]; f = next(f for f in h["fix"] if f.get("fixed")).get("model")
            if s in MODELS and f in MODELS:
                under += MODELS.index(f) > MODELS.index(s); over += MODELS.index(f) < MODELS.index(s)
    R["triage"]["fixed_by_a_higher_model_than_it_started_at"] = under
    # cost
    cost = collections.defaultdict(lambda: {"attempts": 0, "fixed": 0, "usd": 0.0, "secs": 0.0, "cache": 0, "tokens": 0})
    for e in ev:
        if e["event"] == "fix" and e.get("model") != "auto":
            c = cost[rung(e)]
            c["attempts"] += 1; c["fixed"] += bool(e.get("fixed")); c["usd"] += e.get("cost") or 0; c["secs"] += e.get("secs") or 0
            u = e.get("usage") or {}
            c["cache"] += u.get("cache_read") or 0; c["tokens"] += sum((u.get(k) or 0) for k in ("input", "cache_creation", "cache_read"))
    R["cost"] = {k: {**v, "usd": round(v["usd"], 3), "cache_read_share": round(v["cache"] / v["tokens"], 2) if v["tokens"] else None} for k, v in sorted(cost.items())}
    R["cost_total_usd"] = round(sum(v["usd"] for v in cost.values()), 3)
    # recurring failures
    sig = collections.defaultdict(lambda: {"n": 0, "runs": set(), "stage": None, "head": "", "last": "", "healed": 0})
    for e in ev:
        if e["event"] == "stage" and e.get("sig"):
            s = sig[e["sig"]]
            s["n"] += 1; s["runs"].add(e.get("run")); s["stage"] = e.get("stage"); s["head"] = e.get("head", ""); s["last"] = max(s["last"], e.get("ts", ""))
    for (r, st), h in heals.items():
        if any(f.get("fixed") for f in h["fix"]):
            for e in runs[r]:
                if e["event"] == "stage" and e.get("stage") == st and e.get("sig"):
                    sig[e["sig"]]["healed"] += 1; break
    R["recurring"] = [{"sig": k, "n": v["n"], "runs": len(v["runs"]), "stage": v["stage"], "healed": v["healed"], "last": v["last"], "head": v["head"]}
                      for k, v in sorted(sig.items(), key=lambda kv: -kv[1]["n"]) if v["n"] > 1][:10]
    return R


def findings(ev):
    runs, heals = analyse(ev)
    out = []
    fx = [e for e in ev if e["event"] == "fix" and e.get("model") != "auto"]
    noop = [e for e in fx if not e.get("fixed") and not (e.get("files") or [])]
    if noop:
        out.append(("noop-fixers", f"{len(noop)} of {len(fx)} fixer attempt(s) changed nothing and cost ${sum(e.get('cost') or 0 for e in noop):.2f}: "
                    + ", ".join(sorted({f"{e.get('stage')} {rung(e)}" for e in noop})[:6])))
    outside = []
    for r, es in runs.items():
        scope = next((e.get("scope") for e in es if e["event"] == "result" and e.get("scope")), None)
        mods = [m for m in (scope or "").replace(",", " ").split() if m and m != "all" and not m.startswith("args:")]
        if not mods:
            continue
        for e in es:
            if e["event"] == "fix" and e.get("model") != "auto":
                bad = [f for f in (e.get("files") or []) if not any(f == m or f.startswith(m + "/") for m in mods) and not f.startswith(("config/", "gradle/", "build-logic/"))]
                if bad:
                    outside.append(f"{r[:8]} {e.get('stage')}: {', '.join(bad[:3])}")
    if outside:
        out.append(("fixer-outside-module", f"{len(outside)} fixer attempt(s) changed files outside the scoped module(s): " + "; ".join(outside[:4])))
    mism = [r for r, es in runs.items() if any(e["event"] == "result" and e.get("healed") in ("-", "", None) and e.get("status") == "SUCCESS" for e in es)
            and any(e["event"] == "fix" and e.get("fixed") for e in es)]
    if mism:
        out.append(("healed-field-mismatch", f"{len(mism)} run(s) say healed=- although a fixer fixed something: " + ", ".join(m[:8] for m in mism[:5])))
    tr = [e for e in ev if e["event"] == "triage"]
    fb = [e for e in tr if e.get("fallback")]
    if len(tr) >= 3 and len(fb) / len(tr) > 0.2:
        why = collections.Counter(e.get("fallback") for e in fb)
        out.append(("triage-fallbacks", f"{len(fb)} of {len(tr)} triage call(s) fell back to the default level ({', '.join(f'{k}: {v}' for k, v in why.items())}); the first error: "
                    + next((str(e.get("error")) for e in fb if e.get("error")), "none")[:160]))
    lat = [e["secs"] for e in tr if e.get("secs") is not None]
    if len(lat) >= 3 and pct(lat, .95) > 45:
        out.append(("triage-slow", f"triage p95 latency is {pct(lat, .95):.0f}s (Ollama cuts a System One call off at 60s): shorten the brief or the level descriptions"))
    solved = [h for h in heals.values() if any(f.get("fixed") for f in h["fix"]) and h["triage"] and h["triage"].get("route") == "level"]
    under = [h for h in solved if h["triage"].get("start", "/").split("/")[0] in MODELS and next(f for f in h["fix"] if f.get("fixed")).get("model") in MODELS
             and MODELS.index(next(f for f in h["fix"] if f.get("fixed")).get("model")) > MODELS.index(h["triage"]["start"].split("/")[0])]
    if len(solved) >= 5 and len(under) / len(solved) >= 0.5:
        out.append(("under-triage", f"{len(under)} of {len(solved)} triaged heals were fixed by a stronger model than the triage started at: the levels are rated too low"))
    guard = [e for e in fx if e.get("guard")]
    if guard:
        out.append(("guard-hits", f"{len(guard)} fixer attempt(s) hit a guard: " + "; ".join(sorted({str(e['guard'])[:60] for e in guard})[:3])))
    give = [k for k, h in heals.items() if h["giveup"]]
    if give:
        out.append(("giveups", f"{len(give)} heal(s) gave up after every model: " + ", ".join(sorted({str(k[1]) for k in give}))))
    sigs = collections.Counter()
    for e in ev:
        if e["event"] == "stage" and e.get("sig"):
            sigs[(e["sig"], e.get("stage"))] += 1
    rec = [(k, n) for k, n in sigs.items() if n >= 3]
    if rec:
        out.append(("repeated-failure", f"{len(rec)} failure(s) came back three times or more, the worst: {max(rec, key=lambda x: x[1])[0][1]} ({max(n for _, n in rec)} times)"))
    return out


def text(R):
    L = []
    r = R["runs"]
    L.append(f"Runs: {r['with_events']} with events, {r['results']} finished {r['status']}; wall time median {fmt_s(r['wall_median_s'])}, p95 {fmt_s(r['wall_p95_s'])}")
    t = R["time"]; total = sum(t.values()) or 1
    L.append("\nWhere the time goes (summed over the events)")
    for k, v in sorted(t.items(), key=lambda kv: -kv[1]):
        L.append(f"  {k:14} {fmt_s(v):>8}  {100 * v / total:4.0f}%")
    L.append("\nStages            runs  failed  mean    p95")
    for k, v in sorted(R["stages"].items(), key=lambda kv: -kv[1]["mean_s"] * kv[1]["runs"]):
        L.append(f"  {str(k):14} {v['runs']:5} {v['failed']:7} {fmt_s(v['mean_s']):>6} {fmt_s(v['p95_s']):>6}")
    h = R["heals"]
    L.append(f"\nHeals: {h['count']} started, {h['solved']} solved, {h['giveups']} gave up; {h['attempts_per_heal']:.1f} fixer attempt(s) per heal")
    if h["fixed_at"]:
        L.append("  solved at: " + ", ".join(f"{k} x{v}" for k, v in h["fixed_at"].items()))
    tr = R["triage"]
    L.append(f"\nTriage: {tr['calls']} call(s), routes {tr['routes']}, fallbacks {tr['fallbacks'] or 'none'}")
    L.append(f"  latency p50 {fmt_s(tr['latency_p50_s'])}, p95 {fmt_s(tr['latency_p95_s'])}; median confidence {tr['confidence_median']:.2f}; "
             f"{tr['fixed_by_a_higher_model_than_it_started_at']} heal(s) needed a stronger model than the start")
    if tr["start"]:
        L.append("  started at: " + ", ".join(f"{k} x{v}" for k, v in tr["start"].items()))
    L.append(f"\nCost by rung (total ${R['cost_total_usd']})      attempts  fixed   $      time   cache-read")
    for k, v in R["cost"].items():
        cr = "-" if v["cache_read_share"] is None else f"{100 * v['cache_read_share']:.0f}%"
        L.append(f"  {k:26} {v['attempts']:8} {v['fixed']:6} {v['usd']:7.2f} {fmt_s(v['secs']):>7} {cr:>8}")
    if R["recurring"]:
        L.append("\nFailures that came back")
        for x in R["recurring"][:6]:
            L.append(f"  {x['n']}x in {x['runs']} run(s), {x['stage']}, healed {x['healed']}x: {x['head'].splitlines()[0][:110] if x['head'] else x['sig']}")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["report", "analyze", "show"])
    ap.add_argument("--events", default=os.environ.get("YUKTA_HEAL_EVENTS", DEFAULT))
    ap.add_argument("--since"); ap.add_argument("--run"); ap.add_argument("--project"); ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    ev = load(a.events)
    t0 = since(a.since)
    ev = [e for e in ev if (not t0 or e.get("ts", "") >= t0) and (not a.run or str(e.get("run", "")).startswith(a.run)) and (not a.project or e.get("project") == a.project)]
    if not ev:
        print(f"no heal events in {a.events} for this selection (yk check writes them)")
        return 0
    if a.cmd == "show":
        for e in sorted(ev, key=lambda e: e.get("ts", "")):
            print(json.dumps(e, sort_keys=True))
        return 0
    if a.cmd == "analyze":
        f = findings(ev)
        if a.json:
            print(json.dumps([{"rule": r, "evidence": x} for r, x in f], indent=1))
        else:
            for r, x in f:
                print(f"{r}: {x}")
            if not f:
                print("no findings")
        return 1 if f else 0
    R = report(ev)
    print(json.dumps({**R, "findings": [{"rule": r, "evidence": x} for r, x in findings(ev)]}, indent=1, default=list) if a.json else text(R))
    if not a.json:
        f = findings(ev)
        if f:
            print("\nFindings")
            for r, x in f:
                print(f"  {r}: {x}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
