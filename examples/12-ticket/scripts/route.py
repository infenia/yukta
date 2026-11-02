#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit route RUN_DIR: writes RUN_DIR/routing.json (design 3.1, 3.3, 15.8 static rules).

complexity from the acceptance-criteria count (<= 2 S, <= 5 M, else L; more than one ecosystem: at least M); risk high for a risk or
security label or more than one module, medium when no module label is known, else low; kind came from init."""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import die, load, model_table, save, tests_mode_for


def main(argv):
    if len(argv) != 1:
        die("usage: ticketkit route RUN_DIR")
    run = pathlib.Path(argv[0])
    t, p = load(run / "ticket.json"), load(run / "project.json")
    n = len(t["acceptance_criteria"])
    complexity = "S" if n <= 2 else "M" if n <= 5 else "L"
    ecos = {p["module_labels"][m] for m in t["modules"]}
    if len(ecos) > 1 and complexity == "S":
        complexity = "M"
    if {"risk", "security"} & set(t["labels"]) or len(t["modules"]) > 1:
        risk = "high"
    else:
        risk = "low" if t["modules"] else "medium"
    r = {"ticket_id": t["ticket_id"], "kind": t["kind"], "complexity": complexity, "risk": risk, "decided_by": "static",
         "tests_mode": tests_mode_for(t["kind"], complexity, risk), "allow_architect_code": risk == "high",
         "tier_mode": p["tier_mode"], "models": model_table(complexity, risk, p["tier_mode"], p.get("implementer_override")),
         "budget_usd": p["budgets_usd"][complexity]}
    save(run / "routing.json", r)
    print(f"ROUTE complexity={complexity} risk={risk} tests_mode={r['tests_mode']} architect={r['models']['architect']['model']}")


main(sys.argv[1:])
