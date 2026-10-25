#!/usr/bin/env python3
"""Builds the evaluation report page from runs/*/result.json and the sub-flow records.

  python3 -I make_report.py <runs dir> <out.html>

<runs dir> holds A1..B3 folders as `eval.sh run` writes them. Next to it, an optional extra.json adds what the
records lost or the transcripts showed (see results/2026-10-25-1207/extra.json). The text of template.html
belongs to the 2026-10-25 evaluation of issue 1207; for another evaluation use `eval.sh report` or adapt it.
"""
import glob
import json
import os
import statistics

import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if len(sys.argv) != 3:
    sys.exit(__doc__)
RUNS = os.path.abspath(sys.argv[1])
OUT = os.path.abspath(sys.argv[2])
try:
    with open(os.path.join(os.path.dirname(RUNS), "extra.json")) as _f:
        EXTRA = json.load(_f)
except FileNotFoundError:
    EXTRA = {}


def load(path):
    with open(path) as f:
        return json.load(f)


def tok(u):
    return u["input"] + u["cache_creation"] + u["cache_read"] + u["output"]


def usage_norm(u):
    return {
        "input": u.get("input_tokens", u.get("input", 0)) or 0,
        "cache_creation": u.get("cache_creation_input_tokens") or (u.get("cache_creation") if isinstance(u.get("cache_creation"), int) else 0) or 0,
        "cache_read": u.get("cache_read_input_tokens", u.get("cache_read", 0)) or 0,
        "output": u.get("output_tokens", u.get("output", 0)) or 0,
    }


runs = {}
for d in sorted(glob.glob(os.path.join(RUNS, "*"))):
    name = os.path.basename(d)
    res = load(os.path.join(d, "result.json"))
    main = load(os.path.join(d, "main.json"))
    r = {"name": name, "arm": res["arm"], "res": res, "answer": main.get("result", "")}
    r["orch"] = usage_norm(main["usage"])
    r["orch_cost"] = main["total_cost_usd"]
    r["turns"] = main["num_turns"]
    r["wall"] = res["wall_s"]
    r["impl"] = None
    r["fix"] = []
    if res["arm"] == "B":
        impl = load(glob.glob(os.path.join(d, "yukta/.yukta-dev/issue-*/implement.json"))[0])
        r["impl"] = {"cost": impl["cost"], "u": usage_norm(impl["usage"])}
        for f in sorted(glob.glob(os.path.join(d, "yukta-issue-*/.yukta-dev/heal/check/*-[0-9]*.json"))):
            h = load(f)
            r["fix"].append({"file": os.path.basename(f), "stage": h["stage"], "fixed": h["fixed"],
                             "cost": h["cost"], "u": usage_norm(h["usage"]), "files": h.get("files", [])})
    r["total_cost"] = r["orch_cost"] + (r["impl"]["cost"] if r["impl"] else 0) + sum(x["cost"] for x in r["fix"])
    parts = [r["orch"]] + ([r["impl"]["u"]] if r["impl"] else []) + [x["u"] for x in r["fix"]]
    r["tot"] = {k: sum(p[k] for p in parts) for k in ("input", "cache_creation", "cache_read", "output")}
    r["tokens"] = tok(r["tot"])
    r["cache_pct"] = round(100 * r["tot"]["cache_read"] / r["tokens"])
    r["fix_cost"] = sum(x["cost"] for x in r["fix"])
    r["core"] = r["orch_cost"] + (r["impl"]["cost"] if r["impl"] else 0)  # without fixer attempts
    r["core_tokens"] = tok(r["orch"]) + (tok(r["impl"]["u"]) if r["impl"] else 0)
    r["status"] = "RESULT status=" + (r["answer"].split("status=")[1].split()[0] if "status=" in r["answer"] else "?")
    runs[name] = r

A = [runs[k] for k in sorted(runs) if k.startswith("A")]
B = [runs[k] for k in sorted(runs) if k.startswith("B")]
med = statistics.median


def usd(x):
    return f"${x:,.2f}"


def n(x):
    return f"{int(x):,}"


def kt(x):
    return f"{x / 1000:,.0f}k"


def mins(s):
    return f"{s // 60}m {s % 60:02d}s"


a_cost, b_cost = med(r["total_cost"] for r in A), med(r["total_cost"] for r in B)
a_tok, b_tok = med(r["tokens"] for r in A), med(r["tokens"] for r in B)
b_core_cost = med(r["core"] for r in B)
b_core_tok = med(r["core_tokens"] for r in B)
a_wall, b_wall = med(r["wall"] for r in A), med(r["wall"] for r in B)
a_main_tok = med(tok(r["orch"]) for r in A)
b_main_tok = med(tok(r["orch"]) for r in B)
ratio = b_cost / a_cost
lost = EXTRA.get("lost_runs", {})
corr_total = {}
for r in B:
    est = r["total_cost"]
    if r["name"] in lost:
        subs = [r["impl"]["u"]] + [x["u"] for x in r["fix"]]
        rec_tokens = sum(u["cache_creation"] + u["cache_read"] for u in subs)  # sub-flow tokens on record
        sub_cost = r["fix_cost"] + r["impl"]["cost"]
        est += sub_cost * lost[r["name"]]["cache_tokens"] / rec_tokens
    corr_total[r["name"]] = est
b_cost_corr = med(corr_total.values())
ratio_corr = b_cost_corr / a_cost
fix_total = sum(r["fix_cost"] for r in B)
b_total = sum(r["total_cost"] for r in B)
attempts = [x for r in B for x in r["fix"]]
noop = [x for x in attempts if not x["fixed"]]
noop_cost = sum(x["cost"] for x in noop)
cache_all = [r["cache_pct"] for r in runs.values()]
delivered = sum(1 for r in B if "SUCCESS" in r["status"])

# ---------- charts ----------
SEG = {"orch": ("Claude Code session (orchestrator)", "var(--c1)"), "impl": ("Implementer sub-flow", "var(--c2)"),
       "fix": ("Fixer sub-flow (all outside cli/)", "var(--c3)"), "solo": ("Claude Code session doing the work itself", "var(--c4)")}


def bar(r, mx):
    segs = []
    if r["arm"] == "A":
        segs = [("solo", r["orch_cost"])]
    else:
        segs = [("orch", r["orch_cost"]), ("impl", r["impl"]["cost"]), ("fix", r["fix_cost"])]
    html = ""
    for k, v in segs:
        if v <= 0:
            continue
        html += f'<span class="seg" style="width:{100 * v / mx:.2f}%;background:{SEG[k][1]}" title="{SEG[k][0]}: {usd(v)}"></span>'
    note = ""
    if r["name"] == "B2":
        note = ' <span class="tag warn">lower bound</span>'
    return f'<div class="barrow"><div class="barlabel">{r["name"]}</div><div class="bartrack">{html}</div><div class="barval">{usd(r["total_cost"])}{note}</div></div>'


mx = max(r["total_cost"] for r in runs.values()) * 1.04
cost_chart = "".join(bar(runs[k], mx) for k in sorted(runs))
legend = "".join(f'<span class="lg"><i style="background:{c}"></i>{t}</span>' for k, (t, c) in SEG.items())


def tok_chart():
    mxt = max(r["tokens"] for r in runs.values()) * 1.02
    rows = ""
    for k in sorted(runs):
        r = runs[k]
        t = r["tot"]
        cells = [("cache_read", "Cache read", "var(--t3)"), ("cache_creation", "Cache write", "var(--t2)"),
                 ("output", "Output", "var(--t4)"), ("input", "Fresh input", "var(--t1)")]
        inner = ""
        for key, label, col in cells:
            w = 100 * t[key] / mxt
            if w < 0.05:
                continue
            inner += f'<span class="seg" style="width:{w:.2f}%;background:{col}" title="{label}: {n(t[key])}"></span>'
        rows += f'<div class="barrow"><div class="barlabel">{k}</div><div class="bartrack">{inner}</div><div class="barval">{kt(r["tokens"])}</div></div>'
    lg = "".join(f'<span class="lg"><i style="background:{c}"></i>{l}</span>' for _, l, c in
                 [("", "Cache read (re-reading already-stored context)", "var(--t3)"), ("", "Cache write (storing new context)", "var(--t2)"),
                  ("", "Output (what Claude wrote)", "var(--t4)"), ("", "Fresh input", "var(--t1)")])
    return rows, lg


tok_rows, tok_legend = tok_chart()

# ---------- tables ----------


def run_rows():
    out = ""
    for k in sorted(runs):
        r = runs[k]
        h = r["res"]["grade"]
        code_ok = h["hidden_pass"] and h["suite_pass"]
        if r["arm"] == "A":
            deliv = '<span class="tag ok">finished</span>'
        else:
            deliv = ('<span class="tag ok">SUCCESS</span>' if "SUCCESS" in r["status"] else '<span class="tag bad">ESCALATE</span>')
        out += (f'<tr><td><b>{k}</b></td><td class="num">{usd(r["total_cost"])}</td><td class="num">{kt(r["tokens"])}</td>'
                f'<td class="num">{r["cache_pct"]}%</td><td class="num">{r["turns"]}</td><td class="num">{len(r["fix"])}</td>'
                f'<td class="num">{mins(r["wall"])}</td><td>{"pass" if code_ok else "FAIL"}</td><td>{deliv}</td></tr>')
    return out


def breakdown_rows():
    out = ""
    for r in B:
        out += (f'<tr><td><b>{r["name"]}</b></td><td class="num">{usd(r["orch_cost"])}</td><td class="num">{usd(r["impl"]["cost"])}</td>'
                f'<td class="num">{usd(0)}</td><td class="num">{usd(r["fix_cost"])}</td><td class="num"><b>{usd(r["total_cost"])}</b></td>'
                f'<td class="num">{usd(r["core"])}</td></tr>')
    return out


def attempt_rows():
    out = ""
    for r in B:
        for x in r["fix"]:
            files = "<br>".join(f.split("/")[-1] for f in x["files"]) or "—"
            out += (f'<tr><td>{r["name"]}</td><td><code>{x["file"].replace(".json", "")}</code></td>'
                    f'<td>{"changed files" if x["fixed"] else "no change"}</td><td class="num">{usd(x["cost"])}</td>'
                    f'<td class="num">{kt(tok(x["u"]))}</td><td class="num">{kt(x["u"]["cache_read"])}</td><td class="small">{files}</td></tr>')
    return out


def cat_rows():
    out = ""
    for k in sorted(runs):
        t = runs[k]["tot"]
        out += (f'<tr><td><b>{k}</b></td><td class="num">{n(t["input"])}</td><td class="num">{n(t["cache_creation"])}</td>'
                f'<td class="num">{n(t["cache_read"])}</td><td class="num">{n(t["output"])}</td><td class="num"><b>{n(runs[k]["tokens"])}</b></td></tr>')
    return out


def split_rows():
    out = ""
    for k in sorted(runs):
        r = runs[k]
        o = r["orch"]
        if r["arm"] == "A":
            out += f'<tr><td><b>{k}</b></td><td class="num">{n(tok(o))}</td><td class="num">—</td><td class="num">—</td></tr>'
        else:
            fx = sum(tok(x["u"]) for x in r["fix"])
            out += (f'<tr><td><b>{k}</b></td><td class="num">{n(tok(o))}</td><td class="num">{n(tok(r["impl"]["u"]))}</td>'
                    f'<td class="num">{n(fx)}</td></tr>')
    return out


def rewrite_rows():
    out = ""
    for x in EXTRA.get("resume_rewrites", []):
        out += (f'<tr><td>{x["run"]}</td><td class="num">{n(x["gap_s"])} s</td><td class="num">{n(x["write"])}</td>'
                f'<td class="num">{n(x["read"])}</td></tr>')
    return out


html = open(os.path.join(HERE, "template.html")).read()
rep = {
    "{{A_COST}}": usd(a_cost), "{{B_COST}}": usd(b_cost), "{{RATIO}}": f"{ratio:.1f}",
    "{{A_RANGE}}": f"{usd(min(r['total_cost'] for r in A))} to {usd(max(r['total_cost'] for r in A))}",
    "{{B_RANGE}}": f"{usd(min(r['total_cost'] for r in B))} to {usd(max(r['total_cost'] for r in B))}",
    "{{A_TOK}}": kt(a_tok), "{{B_TOK}}": kt(b_tok), "{{A_MAIN_TOK}}": kt(a_main_tok), "{{B_MAIN_TOK}}": kt(b_main_tok),
    "{{B_CORE_COST}}": usd(b_core_cost), "{{B_CORE_TOK}}": kt(b_core_tok),
    "{{A_WALL}}": mins(int(a_wall)), "{{B_WALL}}": mins(int(b_wall)),
    "{{FIX_TOTAL}}": usd(fix_total), "{{B_TOTAL}}": usd(b_total), "{{FIX_SHARE}}": f"{100 * fix_total / b_total:.0f}%",
    "{{NOOP_N}}": str(len(noop)), "{{NOOP_COST}}": usd(noop_cost), "{{ATT_N}}": str(len(attempts)),
    "{{CACHE_MIN}}": f"{min(cache_all)}%", "{{CACHE_MAX}}": f"{max(cache_all)}%",
    "{{DELIVERED}}": str(delivered),
    "{{COST_CHART}}": cost_chart, "{{COST_LEGEND}}": legend, "{{TOK_CHART}}": tok_rows, "{{TOK_LEGEND}}": tok_legend,
    "{{RUN_ROWS}}": run_rows(), "{{BREAKDOWN_ROWS}}": breakdown_rows(), "{{ATTEMPT_ROWS}}": attempt_rows(),
    "{{CAT_ROWS}}": cat_rows(), "{{SPLIT_ROWS}}": split_rows(),
    "{{BASE}}": open(os.path.join(RUNS, "A1", "base")).read().strip()[:9],
    "{{B1_FIX}}": usd(runs["B1"]["fix_cost"]), "{{B1_TOTAL}}": usd(runs["B1"]["total_cost"]),
    "{{B2_REC}}": usd(runs["B2"]["total_cost"]),
    "{{B2_EST}}": usd(corr_total["B2"]), "{{B_COST_CORR}}": usd(b_cost_corr), "{{RATIO_CORR}}": f"{ratio_corr:.1f}",
    "{{B2_LOST_TOK}}": n(lost.get("B2", {}).get("cache_tokens", 0)),
    "{{REWRITE_ROWS}}": rewrite_rows(),
    "{{A_CORE_MED}}": usd(a_cost),
}
for k, v in rep.items():
    html = html.replace(k, v)
assert "{{" not in html, [p for p in html.split("{{")[1:3]]
out = OUT
with open(out, "w") as f:
    f.write(html)
print(out, len(html))
