#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit route RUN_DIR: writes RUN_DIR/routing.json (design 3.1, 3.3, 15.8 static rules).

complexity from the acceptance-criteria count (<= 2 S, <= 5 M, else L; more than one ecosystem: at least M); risk high for a risk or
security label or more than one module, medium when no module label is known, else low; kind came from init."""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import die, load, matches, model_table, save, tests_mode_for


def static(t, p):
    """The static rules (design 15.8): complexity from the criteria count, risk from labels, modules and sensitive paths named in the criteria."""
    n = len(t["acceptance_criteria"])
    complexity = "S" if n <= 2 else "M" if n <= 5 else "L"
    ecos = {p["module_labels"][m] for m in t["modules"]}
    if len(ecos) > 1 and complexity == "S":
        complexity = "M"
    if {"risk", "security"} & set(t["labels"]) or len(t["modules"]) > 1 or path_hits(t, p):
        risk = "high"
    else:
        risk = "low" if t["modules"] else "medium"
    return complexity, risk


def path_hits(t, p):
    """Words of the acceptance criteria that look like a path matching a high_risk_paths glob."""
    words = {w.strip("`'\".,;:()[]") for c in t["acceptance_criteria"] for w in c.split()}
    return sorted(w for w in words if "/" in w and matches(w, p["high_risk_paths"]))


def build(t, p, complexity, risk, decided_by):
    return {"ticket_id": t["ticket_id"], "kind": t["kind"], "complexity": complexity, "risk": risk, "decided_by": decided_by,
            "tests_mode": tests_mode_for(t["kind"], complexity, risk), "allow_architect_code": risk == "high",
            "tier_mode": p["tier_mode"], "models": model_table(complexity, risk, p["tier_mode"], p.get("implementer_override")),
            "budget_usd": p["budgets_usd"][complexity]}


def main(argv):
    if not argv or len(argv) not in (1, 3, 4):
        die("usage: ticketkit route RUN_DIR [--set complexity=S risk=low]")
    run = pathlib.Path(argv[0])
    t, p = load(run / "ticket.json"), load(run / "project.json")
    complexity, risk = static(t, p)
    by = "static"
    for kv in argv[1:]:  # --set k=v...: the decision model (mode on) overrides the static rule
        if "=" in kv:
            k, v = kv.split("=")
            complexity, risk, by = (v, risk, "model") if k == "complexity" else (complexity, v, "model")
    r = build(t, p, complexity, risk, by)
    save(run / "routing.json", r)
    print(f"ROUTE complexity={complexity} risk={risk} tests_mode={r['tests_mode']} architect={r['models']['architect']['model']}")


if __name__ == "__main__":
    main(sys.argv[1:])
