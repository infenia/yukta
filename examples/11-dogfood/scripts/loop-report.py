#!/usr/bin/env python3
"""The improvement loop's report: what each method cost and delivered, with the uncertainty, and what not to conclude.

  python3 -I loop-report.py [--ledger FILE] [--loop SHA] [--min-n 8] [--budget 60]

Reads the ledger of loop.sh (draw, outcome, sync, issue-sync and analysis records). One run is one draw with its outcomes
(a restarted run adds outcomes: costs and times are summed, the last result counts). The method comparison uses the runs that
are finished and neither forced (--method) nor loop-changing; --loop restricts it to one loop commit.

Statistics, kept honest for small samples: medians with a bootstrap interval (90%, seeded, so the same ledger gives the
same report), and a regression of log(cost) on method and size (a ratio per method against the baseline, with a bootstrap
interval). No p-values. A method with fewer than --min-n runs gets no verdict: the numbers are printed, a conclusion is not.
"""
import argparse
import json
import math
import os
import random
import statistics
import sys

USAGE_KEYS = ("gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens",
              "gen_ai.usage.cache_creation.input_tokens", "gen_ai.usage.cache_read.input_tokens")


def read_ledger(path):
    if not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def tokens_of(outcome):
    return sum((outcome.get(part) or {}).get(k, 0) or 0 for part in ("main", "sub") for k in USAGE_KEYS)


def build_runs(records):
    draws = {r["id"]: r for r in records if r.get("type") == "draw"}
    outs, sync = {}, {}
    for r in records:
        if r.get("type") == "outcome":
            outs.setdefault(r.get("draw"), []).append(r)
        elif r.get("type") == "sync":
            sync[r.get("draw")] = r  # the last one wins
    runs = []
    for did, d in draws.items():
        o = outs.get(did)
        if not o:
            continue
        last = o[-1]
        s = sync.get(did) or {}
        runs.append({
            "id": did, "issue": d.get("issue"), "size": d.get("size"), "method": d.get("method"), "forced": bool(d.get("forced")),
            "loop_changing": bool(d.get("loop_changing")), "loop_sha": d.get("loop_sha", ""),
            "backend": ((last.get("nav") or {}).get("backend")), "index_s": ((last.get("nav") or {}).get("index_seconds")),
            "cost": sum(x.get("cost_usd", 0) or 0 for x in o), "tokens": sum(tokens_of(x) for x in o),
            "wall": sum(x.get("wall_s", 0) or 0 for x in o), "attempts": len(o),
            "delivered": str(last.get("result", "")).startswith("RESULT status=SUCCESS"),
            "result": str(last.get("result", "")).split(" ")[1] if " " in str(last.get("result", "")) else "?",
            "ci": s.get("ci"), "review_comments": s.get("review_comments"), "human_commits": s.get("human_commits"),
            "merged": s.get("merged"), "lead_time_h": s.get("lead_time_h"),
        })
    return runs


def median(xs):
    return statistics.median(xs) if xs else float("nan")


def boot_ci(xs, fn=median, n=2000, level=0.90, seed=1):
    if len(xs) < 2:
        return (float("nan"), float("nan"))
    rng = random.Random(seed)
    vals = sorted(fn([rng.choice(xs) for _ in xs]) for _ in range(n))
    lo, hi = vals[int((1 - level) / 2 * n)], vals[int((1 + level) / 2 * n) - 1]
    return lo, hi


def fmt_cost(x):
    return "-" if x != x else f"${x:,.2f}"


def fmt_dur(s):
    return "-" if s != s else (f"{int(s) // 60}m{int(s) % 60:02d}s" if s >= 60 else f"{int(s)}s")


def fmt_tok(x):
    return "-" if x != x else (f"{x / 1e6:.2f}M" if x >= 1e6 else f"{x / 1e3:.0f}k")


def solve(a, b):
    """Gaussian elimination with partial pivoting; None when singular."""
    n = len(a)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        if abs(m[p][c]) < 1e-9:
            return None
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                for k in range(c, n + 1):
                    m[r][k] -= f * m[c][k]
    return [m[i][n] / m[i][i] for i in range(n)]


def ols(rows, methods, sizes, baseline_method, baseline_size):
    """log(cost) on method and size dummies. Returns {method: coefficient} or None when the design is singular."""
    cols_m = [m for m in methods if m != baseline_method]
    cols_s = [s for s in sizes if s != baseline_size]
    x = [[1.0] + [1.0 if r["method"] == m else 0.0 for m in cols_m] + [1.0 if r["size"] == s else 0.0 for s in cols_s] for r in rows]
    y = [math.log(max(r["cost"], 1e-6)) for r in rows]
    k = len(x[0])
    xtx = [[sum(row[i] * row[j] for row in x) for j in range(k)] for i in range(k)]
    xty = [sum(row[i] * yy for row, yy in zip(x, y)) for i in range(k)]
    beta = solve(xtx, xty)
    return None if beta is None else dict(zip(cols_m, beta[1:1 + len(cols_m)]))


def table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ledger", default=None)
    ap.add_argument("--loop", default=None, help="only runs of this loop commit in the comparison")
    ap.add_argument("--min-n", type=int, default=8)
    ap.add_argument("--budget", type=float, default=float(os.environ.get("YUKTA_LOOP_BUDGET_TOTAL", 60)))
    a = ap.parse_args()
    ledger = a.ledger or os.path.join(os.environ.get("YUKTA_LOOP_DIR") or os.path.join(
        os.environ.get("YUKTA_DEV_HOME") or os.path.expanduser("~/.local/state/yukta-dev"), "loop"), "ledger.jsonl")
    records = read_ledger(ledger)
    runs = build_runs(records)
    if not runs:
        print(f"No finished runs in {ledger} yet (yk pick draws and runs one).")
        return 0
    comp = [r for r in runs if not r["forced"] and not r["loop_changing"] and (a.loop is None or r["loop_sha"].startswith(a.loop))]
    methods = sorted({r["method"] for r in comp})
    excluded = [r for r in runs if r not in comp]
    shas = sorted({r["loop_sha"] for r in comp})
    spent = sum((r.get("cost_usd") or 0) + (r.get("analyzer_cost_usd") or 0) for r in records if r.get("type") in ("outcome", "analysis", "themes"))
    out = []
    out.append(f"# Improvement loop report\n\nLedger `{ledger}`: {len(runs)} finished run(s); {len(comp)} in the method comparison"
               f" ({len(excluded)} left out: forced, loop-changing or another loop commit). Spent ${spent:,.2f} of ${a.budget:,.0f}.")
    if len(shas) > 1:
        out.append(f"\n> The comparison spans {len(shas)} loop commits ({', '.join(shas)}). Use `--loop <sha>` to compare within one.")

    out.append("\n## Runs\n")
    rows = []
    for r in sorted(runs, key=lambda r: r["id"]):
        flags = ",".join(f for f, on in (("forced", r["forced"]), ("loop-changing", r["loop_changing"]), ("restarted", r["attempts"] > 1)) if on) or "-"
        nav = f"{r['backend']} {r['index_s']}s" if r["backend"] else "-"
        rows.append((f"#{r['issue']}", r["size"], r["method"], nav, r["loop_sha"], fmt_cost(r["cost"]), fmt_tok(r["tokens"]), fmt_dur(r["wall"]),
                     r["result"], r["ci"] or "-", "-" if r["review_comments"] is None else r["review_comments"], flags))
    out.append(table(["issue", "size", "method", "nav", "loop", "cost", "tokens", "wall", "result", "CI", "review comments", "flags"], rows))

    out.append("\n## Per method (comparison set)\n")
    mrows = []
    for m in methods:
        rs = [r for r in comp if r["method"] == m]
        c = [r["cost"] for r in rs]
        lo, hi = boot_ci(c)
        warn = "" if len(rs) >= a.min_n else f" (n<{a.min_n})"
        mrows.append((m + warn, len(rs), f"{sum(r['delivered'] for r in rs)}/{len(rs)}", f"{fmt_cost(median(c))} [{fmt_cost(lo)}..{fmt_cost(hi)}]",
                      fmt_tok(median([r["tokens"] for r in rs])), fmt_dur(median([r["wall"] for r in rs]))))
    out.append(table(["method", "n", "delivered", "median cost [90% interval]", "median tokens", "median wall"], mrows) if mrows else "No run in the comparison set.")

    out.append("\n## Per method and size (median cost, n)\n")
    sizes = [s for s in ("S", "M", "L") if any(r["size"] == s for r in comp)]
    srows = []
    for m in methods:
        cells = []
        for s in sizes:
            rs = [r["cost"] for r in comp if r["method"] == m and r["size"] == s]
            cells.append(f"{fmt_cost(median(rs))} (n={len(rs)})" if rs else "-")
        srows.append([m] + cells)
    if srows:
        out.append(table(["method"] + sizes, srows))

    out.append("\n## Comparing the methods\n")
    short = [m for m in methods if len([r for r in comp if r["method"] == m]) < a.min_n]
    if len(methods) < 2:
        out.append("Only one method has runs: nothing to compare yet.")
    elif short:
        out.append("**No verdict.** " + ", ".join(f"method {m} has n={len([r for r in comp if r['method'] == m])}" for m in short) +
                   f"; a comparison needs at least {a.min_n} runs per method. The numbers above show a direction, not a result.")
    else:
        base = "A" if "A" in methods else methods[0]
        bsize = sizes[0] if sizes else "S"
        beta = ols(comp, methods, sizes, base, bsize)
        if beta is None:
            out.append("**No verdict.** The runs do not cover the methods and sizes well enough for a size-adjusted comparison.")
        else:
            rng = random.Random(1)
            boots = {m: [] for m in beta}
            for _ in range(500):
                sample = [rng.choice(comp) for _ in comp]
                b = ols(sample, methods, sizes, base, bsize)
                if b:
                    for m in beta:
                        boots[m].append(b[m])
            brows = []
            for m in sorted(beta):
                vals = sorted(boots[m])
                lo, hi = (vals[int(0.05 * len(vals))], vals[int(0.95 * len(vals)) - 1]) if len(vals) >= 20 else (float("nan"), float("nan"))
                clear = "yes" if lo == lo and (lo > 0 or hi < 0) else "no"
                brows.append((f"{m} vs {base}", f"x{math.exp(beta[m]):.2f}", f"[x{math.exp(lo):.2f}..x{math.exp(hi):.2f}]" if lo == lo else "-", clear))
            out.append(f"Cost ratio per method against {base}, adjusted for size (log-cost regression on method and size, {len(comp)} runs, 90% bootstrap interval). "
                       "'Clear' means the interval excludes x1.00; it is not a p-value.\n")
            out.append(table(["method", "cost ratio", "interval", "clear"], brows))
        pts = {m: (median([r["cost"] for r in comp if r["method"] == m]), sum(r["delivered"] for r in comp if r["method"] == m) / len([r for r in comp if r["method"] == m])) for m in methods}
        front = [m for m, (c, d) in pts.items() if not any(c2 <= c and d2 >= d and (c2 < c or d2 > d) for m2, (c2, d2) in pts.items() if m2 != m)]
        out.append(f"\nCost against delivered rate: Pareto set (no other method is cheaper and delivers at least as often): {', '.join(sorted(front))}.")

    lc = [r for r in runs if r["loop_changing"] or r["forced"]]
    out.append("\n## Left out of the comparison\n")
    out.append(table(["issue", "method", "why", "cost", "result"], [(f"#{r['issue']}", r["method"], "forced" if r["forced"] else "changes the loop", fmt_cost(r["cost"]), r["result"]) for r in lc]) if lc else "None.")

    out.append("\n## After the pull request (from `yk loop sync`)\n")
    prows = []
    for m in methods:
        rs = [r for r in comp if r["method"] == m and r["ci"] is not None]
        if not rs:
            continue
        prows.append((m, len(rs), f"{sum(r['ci'] == 'success' for r in rs)}/{len(rs)}", f"{median([r['review_comments'] or 0 for r in rs]):g}",
                      f"{median([r['human_commits'] or 0 for r in rs]):g}", f"{sum(bool(r['merged']) for r in rs)}/{len(rs)}"))
    out.append(table(["method", "synced", "CI passed", "median review comments", "median human commits", "merged"], prows) if prows else "No pull request synced yet (`yk loop sync`).")

    analyses = [r for r in records if r.get("type") == "analysis" and not r.get("dry_run")]
    filed = [f for r in analyses for f in r.get("filed", []) if f.get("action") == "file" and f.get("url")]
    comments = sum(1 for r in analyses for f in r.get("filed", []) if f.get("action") == "comment")
    state = {}
    for r in records:
        if r.get("type") == "issue-sync":
            state[r.get("issue")] = r
    approved = rejected = pending = 0
    for f in filed:
        n = int(str(f["url"]).rsplit("/", 1)[-1]) if str(f["url"]).rsplit("/", 1)[-1].isdigit() else None
        s = state.get(n, {})
        if s.get("state") == "closed" and s.get("state_reason") == "completed":
            approved += 1
        elif s.get("state") == "closed":
            rejected += 1
        else:
            pending += 1
    costs = [r.get("analyzer_cost_usd", 0) for r in analyses if r.get("analyzer_cost_usd", 0) > 0]
    out.append("\n## The analyzer\n")
    out.append(f"{len(analyses)} analysis run(s), judge cost median {fmt_cost(median(costs))} per run, {fmt_cost(sum(costs))} in total. "
               f"It commented on known issues {comments} time(s) and filed {len(filed)} new issue(s): {approved} done, {rejected} closed without being done, {pending} still open. "
               + (f"Precision so far: {approved}/{approved + rejected} of the decided ones." if approved + rejected else "No filed issue is decided yet, so there is no precision to show."))
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
