#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""The code side of the decision model (design 3.2, 15.8); scripts/decision.sh asks the model and calls this around it.

  decision.py inputs NAME RUN [STEP]        JSON {state, static}: the digest the model reads (per-file facts, never a diff) and the label the static
                                            rule gives. NAME: triage_complexity | triage_risk | cause | merge_risk
  decision.py apply NAME RUN LABEL [STEP]   use LABEL (the model's, mode on, or the static rule's): triage rewrites routing.json, cause and merge_risk
                                            are written to cause.json / merge_risk.json in the run (cause: steps/STEP/cause.json)
The static rules decide in shadow mode; labels, not a model, give kind, module and clarity."""
import json, pathlib, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, cost_by_role, die, git, load, save
from route import build, path_hits, static as route_static  # noqa: E402  (route.py runs main() on import unless guarded)


def last_attempt(run, sid):
    nums = sorted(int(d.name.split("-")[1]) for d in (run / "steps" / sid).glob("attempt-*"))
    return nums[-1] if nums else 1


def diff_facts(run, wt=None):
    """files / added / removed since the first step base, from git numstat (counts only)."""
    bases = sorted((run / "steps").glob("*/base"))
    if not bases or wt is None:
        return 0, 0, 0
    out = git("diff", "--numstat", bases[0].read_text().strip(), "HEAD", cwd=wt, check=False).stdout.split("\n")
    rows = [l.split("\t") for l in out if l.strip()]
    num = lambda x: int(x) if x.isdigit() else 0
    return len(rows), sum(num(r[0]) for r in rows), sum(num(r[1]) for r in rows)


def merge_static(risk, files, added, removed, revisions):
    lines = added + removed
    return str(min(10, {"low": 1, "medium": 4, "high": 7}[risk] + (2 if lines > 400 else 1 if lines > 150 else 0) + min(1, revisions)))


def inputs(name, run, sid, wt):
    t, p = load(run / "ticket.json"), load(run / "project.json")
    cx, rk = route_static(t, p)
    eco = sorted({p["module_labels"][m] for m in t["modules"]})
    if name == "triage_complexity":
        return {"state": f"kind={t['kind']} criteria={len(t['acceptance_criteria'])} modules={','.join(t['modules']) or '-'} ecosystems={','.join(eco) or '-'}", "static": cx}
    if name == "triage_risk":
        return {"state": (f"labels={','.join(l for l in t['labels'] if l in ('risk', 'security')) or '-'} modules={len(t['modules'])} "
                          f"sensitive_paths={len(path_hits(t, p))} kind={t['kind']}"), "static": rk}
    if name == "cause":
        n = last_attempt(run, sid)
        f = load(run / "steps" / sid / f"attempt-{n}" / "failure.json")
        prev = run / "steps" / sid / f"attempt-{n - 1}" / "failure.json"
        same = prev.is_file() and load(prev).get("message") == f.get("message")
        return {"state": (f"stage={f.get('stage')} class={f.get('class')} attempt={n} failing={len(f.get('failing', []))} repeated={'yes' if same else 'no'} "
                          f"message={f.get('message', '')[:200]}"), "static": "impl_bug"}  # unknown -> impl_bug (15.5)
    if name == "merge_risk":
        files, added, removed = diff_facts(run, wt)
        rv = load(run / "revisions.json")["count"] if (run / "revisions.json").is_file() else 0
        risk = load(run / "routing.json")["risk"]
        return {"state": f"risk={risk} files={files} added={added} removed={removed} modules={len(t['modules'])} revisions={rv}", "static": merge_static(risk, files, added, removed, rv)}
    die(f"unknown decision {name}")


def apply(name, run, label, sid):
    if name in ("triage_complexity", "triage_risk"):
        r = load(run / "routing.json")
        cx, rk = (label, r["risk"]) if name == "triage_complexity" else (r["complexity"], label)
        if (cx, rk) != (r["complexity"], r["risk"]):
            subprocess.run([sys.executable, str(KIT / "scripts" / "route.py"), str(run), "--set", f"complexity={cx}", f"risk={rk}"], check=True, capture_output=True)
    elif name == "cause":
        save(run / "steps" / sid / "cause.json", {"cause": label, "attempt": last_attempt(run, sid)})
    else:
        save(run / "merge_risk.json", {"merge_risk": int(label)})


def main():
    a = sys.argv[1:]
    if len(a) < 3:
        die(__doc__)
    if a[0] == "inputs":
        wt = pathlib.Path(a[4]).resolve() if len(a) > 4 else None
        print(json.dumps(inputs(a[1], pathlib.Path(a[2]), a[3] if len(a) > 3 and a[3] != "-" else None, wt)))
    elif a[0] == "apply":
        apply(a[1], pathlib.Path(a[2]), a[3], a[4] if len(a) > 4 else None)
    else:
        die(__doc__)


main()
