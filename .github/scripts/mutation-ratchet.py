#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Keeps the mutation score of each module from going down.

Usage: mutation-ratchet.py [--baseline FILE] [--root DIR]            compare, exit 1 on a drop
       mutation-ratchet.py [--baseline FILE] [--root DIR] --update   raise the baseline

Reads every <module>/build/reports/pitest/mutations.xml under the root (written by
`./gradlew pitest -Pmutation`). The score of a module is the share of its mutants the tests detect
(killed, timed out or out of memory), not counting mutants that do not compile. A module may lose
TOLERANCE before the check fails, because timeouts make a rerun differ slightly. A module with no
report is skipped, so a partial (incremental) run only checks what it ran. --update raises
baselines and adds new modules; it never lowers one.
"""
import argparse
import json
import math
import pathlib
import sys
import xml.etree.ElementTree as ET

TOLERANCE = 0.01
DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR"}
IGNORED = {"NON_VIABLE"}


def scores(root: pathlib.Path) -> dict[str, tuple[float, int]]:
    """Module -> (score, mutants) for every pitest report under root."""
    found = {}
    for report in sorted(root.glob("**/build/reports/pitest/mutations.xml")):
        module = report.parent.parent.parent.parent.relative_to(root).as_posix()
        statuses = [m.get("status") for m in ET.parse(report).getroot().iter("mutation")]
        counted = [s for s in statuses if s not in IGNORED]
        if counted:
            found[module] = (sum(s in DETECTED for s in counted) / len(counted), len(counted))
    return found


def floor3(value: float) -> float:
    return math.floor(value * 1000) / 1000


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", default="config/mutation/baseline.json")
    parser.add_argument("--root", default=".")
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args()
    baseline_file = pathlib.Path(args.baseline)
    baseline = json.loads(baseline_file.read_text()) if baseline_file.exists() else {}
    current = scores(pathlib.Path(args.root))
    if not current:
        print("mutation-ratchet: no pitest report found; run ./gradlew pitest -Pmutation first")
        return 2

    failed = False
    for module, (score, mutants) in sorted(current.items()):
        known = baseline.get(module)
        if known is None:
            verdict = "new"
        elif score + TOLERANCE < known:
            verdict, failed = "DROPPED", True
        else:
            verdict = "ok"
        print(f"{verdict:8} {module:50} {score:6.1%} ({mutants} mutants)"
              + (f"  baseline {known:.1%}" if known is not None else ""))

    if args.update:
        for module, (score, _) in current.items():
            baseline[module] = max(baseline.get(module, 0.0), floor3(score))
        baseline_file.parent.mkdir(parents=True, exist_ok=True)
        baseline_file.write_text(json.dumps(dict(sorted(baseline.items())), indent=2) + "\n")
        print(f"baseline written to {baseline_file}")
        return 0
    if failed:
        print("mutation-ratchet: a score dropped; add tests for the new code, or see the report in "
              "<module>/build/reports/pitest/index.html")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
