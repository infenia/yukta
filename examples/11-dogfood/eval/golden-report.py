#!/usr/bin/env python3
"""Report of the golden-set replays: methods compared on the same issues, with repeated trials.

  python3 -I golden-report.py --ledger FILE [--loop SHA] [--against SHA] [--min-issues 5]

--loop SHA     the loop commit to report (default: the one with the latest run).
--against SHA  compare that loop commit with this one: per method, the change in cost and in success on the same issues.

Per issue and method there are k trials. pass^k is the chance that all k of them succeed (reliability; unbiased estimate
C(c,k)/C(n,k) from c successes in n trials, averaged over issues); the spread of the cost over the trials of one cell is the
coefficient of variation. The comparison across methods is paired by issue: per issue the ratio of the median costs, then the
geometric mean over issues with a bootstrap interval over issues. With fewer than --min-issues issues there is no verdict: the
per-issue numbers are shown, a conclusion across issues is not.
"""
import argparse
import json
import math
import os
import random
import statistics
import sys


def read(path):
    out = []
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def med(xs):
    return statistics.median(xs) if xs else float("nan")


def boot(xs, fn, n=2000, seed=1, level=0.90):
    if len(xs) < 2:
        return (float("nan"), float("nan"))
    rng = random.Random(seed)
    vals = sorted(fn([rng.choice(xs) for _ in xs]) for _ in range(n))
    return vals[int((1 - level) / 2 * n)], vals[int((1 + level) / 2 * n) - 1]


def gmean(xs):
    return math.exp(sum(math.log(x) for x in xs) / len(xs))


def usd(x):
    return "-" if x != x else f"${x:,.2f}"


def table(h, rows):
    return "\n".join(["| " + " | ".join(h) + " |", "|" + "|".join("---" for _ in h) + "|"] + ["| " + " | ".join(str(c) for c in r) + " |" for r in rows])


def cells_of(runs):
    cells = {}
    for r in runs:
        cells.setdefault((r["issue"], r["method"]), []).append(r)
    return cells


def pass_pow(cells, method, k):
    vals = [math.comb(sum(1 for r in rs if r["success"]), k) / math.comb(len(rs), k) for (i, m), rs in cells.items() if m == method and len(rs) >= k]
    return sum(vals) / len(vals) if vals else None


def ratio_table(cells, a, b, issues, label):
    """per issue the ratio of the median cost of method/loop a over b; returns rows and the ratios"""
    rows, ratios = [], []
    for i in issues:
        ca, cb = cells.get((i, a)), cells.get((i, b))
        if not ca or not cb:
            continue
        ma, mb = med([r["cost_usd"] for r in ca]), med([r["cost_usd"] for r in cb])
        if ma > 0 and mb > 0:
            ratios.append(ma / mb)
            rows.append((f"#{i}", usd(ma), usd(mb), f"x{ma / mb:.2f}", f"{sum(r['success'] for r in ca)}/{len(ca)} vs {sum(r['success'] for r in cb)}/{len(cb)}"))
    return rows, ratios


def verdict(ratios, min_issues, what):
    if len(ratios) < min_issues:
        return f"**No verdict.** {what}: only {len(ratios)} issue(s) have both sides; a comparison across issues needs at least {min_issues}. The per-issue ratios above show the direction."
    lo, hi = boot(ratios, gmean)
    clear = "yes" if lo > 1 or hi < 1 else "no"
    return f"{what}: geometric mean cost ratio **x{gmean(ratios):.2f}** over {len(ratios)} issues, 90% bootstrap interval [x{lo:.2f}..x{hi:.2f}], clear (interval excludes x1.00): {clear}."


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ledger", required=True)
    ap.add_argument("--loop", default=None)
    ap.add_argument("--against", default=None)
    ap.add_argument("--min-issues", type=int, default=5)
    a = ap.parse_args()
    records = read(a.ledger)
    allruns = [r for r in records if r.get("type") == "run"]
    if not allruns:
        print(f"No golden-set run in {a.ledger} yet (golden.sh run).")
        return 0
    loop = a.loop or allruns[-1]["loop_sha"]
    runs = [r for r in allruns if r.get("loop_sha", "").startswith(loop)]
    if not runs:
        print(f"No run of loop commit {loop} in {a.ledger}.")
        return 1
    cells = cells_of(runs)
    methods = sorted({m for _, m in cells})
    issues = sorted({i for i, _ in cells})
    sizes = [len(rs) for rs in cells.values()]
    out = [f"# Golden-set report, loop commit {loop}\n",
           f"{len(runs)} run(s) in replay(s) {', '.join(sorted({r['replay'] for r in runs}))}: {len(issues)} issue(s), methods {', '.join(methods)}, "
           f"{min(sizes)} to {max(sizes)} trial(s) per issue and method. Cost spent so far on all replays: {usd(sum(r.get('cost_usd', 0) for r in allruns))}."]
    maxk = max(sizes)

    out.append("\n## Per method, all issues\n")
    rows = []
    for m in methods:
        rs = [r for r in runs if r["method"] == m]
        c = [r["cost_usd"] for r in rs]
        lo, hi = boot(c, med)
        cvs = [statistics.pstdev([r["cost_usd"] for r in x]) / statistics.mean([r["cost_usd"] for r in x])
               for (i, mm), x in cells.items() if mm == m and len(x) >= 2 and statistics.mean([r["cost_usd"] for r in x]) > 0]
        pk = []
        for k in range(1, maxk + 1):
            v = pass_pow(cells, m, k)
            pk.append(f"{v:.2f}" if v is not None else "-")
        rows.append((m, len(rs), f"{sum(r['success'] for r in rs)}/{len(rs)}", " / ".join(pk), f"{usd(med(c))} [{usd(lo)}..{usd(hi)}]",
                     f"{med(cvs) * 100:.0f}%" if cvs else "-", sum(1 for r in rs if r.get("failed"))))
    out.append(table(["method", "runs", "success", f"pass^1..{maxk}", "median cost [90%]", "cost spread (CV)", "failed to run"], rows))
    out.append("\nSuccess = the code is correct and, for C, the workflow ended SUCCESS. pass^k = the chance that k trials in a row all succeed.")

    out.append("\n## Per issue and method\n")
    rows = []
    for i in issues:
        for m in methods:
            rs = cells.get((i, m))
            if rs:
                c = [r["cost_usd"] for r in rs]
                rows.append((f"#{i}", m, len(rs), f"{sum(r['success'] for r in rs)}/{len(rs)}", usd(med(c)), f"{usd(min(c))}..{usd(max(c))}",
                             ", ".join(sorted({r["backend"] for r in rs if r.get("backend")})) or "-"))
    out.append(table(["issue", "method", "trials", "success", "median cost", "range", "nav backend"], rows))

    out.append("\n## Comparing the methods, paired by issue\n")
    base = "A" if "A" in methods else methods[0]
    for m in methods:
        if m == base:
            continue
        rows, ratios = ratio_table(cells, m, base, issues, f"{m} vs {base}")
        out.append(f"\n**{m} against {base}**\n")
        out.append(table(["issue", f"median {m}", f"median {base}", "ratio", "success"], rows) if rows else "No issue has both.")
        out.append("\n" + verdict(ratios, a.min_issues, f"{m} vs {base}"))

    if a.against:
        other = [r for r in allruns if r.get("loop_sha", "").startswith(a.against)]
        out.append(f"\n## Loop commit {loop} against {a.against}\n")
        if not other:
            out.append(f"No run of loop commit {a.against}.")
        else:
            oc = cells_of(other)
            both = {}
            for (i, m), rs in cells.items():
                if (i, m) in oc:
                    both[(i, m)] = (rs, oc[(i, m)])
            for m in methods:
                rows, ratios = [], []
                for (i, mm), (x, y) in sorted(both.items()):
                    if mm == m and med([r["cost_usd"] for r in x]) > 0 and med([r["cost_usd"] for r in y]) > 0:
                        ratio = med([r["cost_usd"] for r in x]) / med([r["cost_usd"] for r in y])
                        ratios.append(ratio)
                        rows.append((f"#{i}", usd(med([r["cost_usd"] for r in x])), usd(med([r["cost_usd"] for r in y])), f"x{ratio:.2f}",
                                     f"{sum(r['success'] for r in x)}/{len(x)} vs {sum(r['success'] for r in y)}/{len(y)}"))
                if rows:
                    out.append(f"\n**Method {m}**\n")
                    out.append(table(["issue", f"cost at {loop}", f"cost at {a.against}", "ratio", "success"], rows))
                    out.append("\n" + verdict(ratios, a.min_issues, f"Method {m}, {loop} vs {a.against}"))
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
