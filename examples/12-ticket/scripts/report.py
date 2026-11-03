#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit report [--events FILE | --run RUN_DIR]... [--json] (design 11 tuning loop, 11.1, 11.3)

From ticket-events.jsonl (default $YUKTA_TICKET_EVENTS, else ~/.local/state/yukta-dev/ticket-events.jsonl):
  first-try pass rate and mean attempts by (model, effort, complexity)   the tuning loop: Haiku below 70% on standard steps -> Sonnet/medium
  cost share by role, cache hit ratio by role                            architect + test_writer above half the cost on M/L -> revisit tests_mode
  prefix_hash mismatches                                                 two different prefixes in one run without a revision in between: a bug
  BLOCKED rate                                                           above 15% the steps are too small: retune the sizing lint (design 11.3)"""
import argparse, collections, json, os, pathlib, sys

BLOCKED_LIMIT = 0.15


def read(paths):
    out = []
    for p in paths:
        f = pathlib.Path(p)
        if f.is_file():
            out += [json.loads(l) for l in f.read_text().splitlines() if l.strip()]
    return out


def build(events):
    runs = collections.defaultdict(list)
    for e in events:
        runs[e["run_id"]].append(e)
    attempts = collections.defaultdict(dict)  # (run, step) -> {attempt: outcome}
    for rid, evs in runs.items():
        for e in evs:
            if e.get("role") == "validate":
                attempts[(rid, e["step_id"])][e["attempt"]] = {"ok": e["status"] == "pass", "key": (e.get("model"), e.get("effort"), e.get("complexity"))}
            elif e.get("role") == "decide" and e.get("fail_class") == "blocked_reported":
                attempts[(rid, e["step_id"])][e["attempt"]] = {"ok": False, "blocked": True, "key": (e.get("model"), e.get("effort"), e.get("complexity"))}
    by_key = collections.defaultdict(lambda: {"steps": 0, "first_try": 0, "attempts": 0})
    n_att = n_blocked = 0
    for (rid, sid), att in attempts.items():
        first = att.get(1) or att[min(att)]
        k = by_key[first["key"]]
        k["steps"] += 1; k["first_try"] += first["ok"]; k["attempts"] += len(att)
        n_att += len(att); n_blocked += sum(1 for a in att.values() if a.get("blocked"))
    cost, cache = collections.Counter(), collections.defaultdict(lambda: [0, 0])
    for e in events:
        if e.get("cost_usd"):
            cost[e["role"]] += e["cost_usd"]
        if "cache_read_tokens" in e:
            c = cache[e["role"]]
            c[0] += e["cache_read_tokens"]; c[1] += e["cache_read_tokens"] + e.get("cache_write_tokens", 0) + e.get("tokens_in", 0)
    mismatch = []
    for rid, evs in runs.items():
        hashes = {e["prefix_hash"] for e in evs if e.get("prefix_hash")}
        revisions = sum(1 for e in evs if e.get("role") == "architect_revise")
        if len(hashes) > 1 + revisions:
            mismatch.append({"run_id": rid, "prefixes": len(hashes), "revisions": revisions})
    total = sum(cost.values())
    return {"runs": len(runs), "steps": len(attempts),
            "by_model": [{"model": k[0], "effort": k[1], "complexity": k[2], **v, "first_try_rate": round(v["first_try"] / v["steps"], 2), "mean_attempts": round(v["attempts"] / v["steps"], 2)}
                         for k, v in sorted(by_key.items(), key=lambda kv: tuple(map(str, kv[0])))],
            "cost_usd": round(total, 4), "cost_share": {r: round(c / total, 2) for r, c in sorted(cost.items())} if total else {},
            "cache_hit": {r: round(c[0] / c[1], 2) if c[1] else None for r, c in sorted(cache.items())},
            "prefix_mismatches": mismatch, "blocked_rate": round(n_blocked / n_att, 2) if n_att else 0.0, "blocked_too_small": bool(n_att and n_blocked / n_att > BLOCKED_LIMIT)}


def text(r):
    L = [f"ticket report: {r['runs']} runs, {r['steps']} steps, cost ${r['cost_usd']:.2f}", "", "first-try pass rate and mean attempts by (model, effort, complexity):"]
    L += [f"  {m['model']}/{m['effort']}/{m['complexity']}: {m['steps']} steps, first try {m['first_try_rate']:.0%}, {m['mean_attempts']} attempts" for m in r["by_model"]] or ["  no steps yet"]
    L += ["", "cost share by role: " + (", ".join(f"{k} {v:.0%}" for k, v in r["cost_share"].items()) or "-"),
          "cache hit ratio by role: " + (", ".join(f"{k} {'-' if v is None else format(v, '.0%')}" for k, v in r["cache_hit"].items()) or "-")]
    L.append("prefix_hash mismatches: " + (", ".join(f"{m['run_id']} ({m['prefixes']} prefixes, {m['revisions']} revisions)" for m in r["prefix_mismatches"]) or "none"))
    L.append(f"BLOCKED rate: {r['blocked_rate']:.0%}" + (" (above 15%: the steps are too small; retune the sizing lint)" if r["blocked_too_small"] else ""))
    for m in r["by_model"]:
        if m["model"] == "haiku" and m["complexity"] == "standard" and m["first_try_rate"] < 0.7 and m["steps"] >= 5:
            L.append("tuning: haiku first-try pass rate on standard steps is below 70%: make Sonnet/medium the default for them")
    return "\n".join(L)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--events", action="append", default=[]); a.add_argument("--run", action="append", default=[]); a.add_argument("--json", action="store_true")
    o = a.parse_args()
    paths = o.events + [str(pathlib.Path(r) / "events.jsonl") for r in o.run]
    if not paths:
        paths = [os.environ.get("YUKTA_TICKET_EVENTS") or str(pathlib.Path(os.environ.get("YUKTA_DEV_HOME", pathlib.Path.home() / ".local/state/yukta-dev")) / "ticket-events.jsonl")]
    r = build(read(paths))
    print(json.dumps(r, indent=1) if o.json else text(r))


main()
