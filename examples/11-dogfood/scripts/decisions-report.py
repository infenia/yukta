#!/usr/bin/env python3
"""yk stats --decisions: how the decision model did where it ran in shadow, from .yukta-dev/decisions.tsv (see decision_log in _lib.sh).

  decisions-report.py TSV DECISIONS-JSON

A decision row is: ts, "decision", name, hash, mode, label, probability, ms. An outcome row is: ts, "outcome", name, hash, -, real label, -, -.
They join on (name, hash); the last outcome wins. Per decision it prints how many were made and joined, the agreement with the real outcome,
the mean latency, a calibration table (predicted probability bucket against the observed agreement) and whether the bar written for the
decision in decisions.json ("bar": {"minN", "minAgreement"}) is met; a decision that does not meet it stays in shadow.
"""
import csv, json, sys
from collections import defaultdict

BUCKETS = [(0.0, 0.5), (0.5, 0.7), (0.7, 0.9), (0.9, 1.01)]
tsv, cfg = sys.argv[1], sys.argv[2]
bars = {k: v.get("bar") for k, v in json.load(open(cfg)).get("decisions", {}).items()}
decisions, outcomes = [], {}
try:
    for r in csv.reader(open(tsv), delimiter="\t"):
        if len(r) < 8:
            continue
        if r[1] == "decision":
            decisions.append(r)
        elif r[1] == "outcome":
            outcomes[(r[2], r[3])] = r[5]
except FileNotFoundError:
    pass
if not decisions:
    print("no decisions recorded yet (they are made in shadow mode by the steps that use the decision model)")
    sys.exit(0)

by = defaultdict(list)
for r in decisions:
    by[r[2]].append(r)
for name, rows in sorted(by.items()):
    joined = [(r, outcomes[(name, r[3])]) for r in rows if (name, r[3]) in outcomes]
    agree = sum(1 for r, o in joined if r[5] == o)
    ms = [float(r[7]) for r in rows if r[7].replace(".", "").isdigit()]
    rate = agree / len(joined) if joined else 0.0
    print(f"{name}: {len(rows)} decisions, {len(joined)} with an outcome, agreement {agree}/{len(joined)}"
          f"{f' = {rate:.0%}' if joined else ''}, mean {sum(ms) / len(ms):.0f} ms" if ms else f"{name}: {len(rows)} decisions")
    for lo, hi in BUCKETS:
        b = [(r, o) for r, o in joined if lo <= float(r[6]) < hi]
        if b:
            k = sum(1 for r, o in b if r[5] == o)
            print(f"  p {lo:.1f}-{min(hi, 1.0):.1f}: {len(b)} decisions, right {k} ({k / len(b):.0%})")
    differ = len(joined) - agree
    print(f"  routing by it would have differed from what happened in {differ} of {len(joined)} cases")
    bar = bars.get(name)
    if bar:
        ok = len(joined) >= bar["minN"] and rate >= bar["minAgreement"]
        print(f"  bar (n >= {bar['minN']}, agreement >= {bar['minAgreement']:.0%}): {'met, may be promoted to on' if ok else 'not met, stays in shadow'}")
    else:
        print("  no bar in decisions.json: stays in shadow")
