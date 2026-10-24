#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Findings for auto-found.sh from what junit-failures.py --failed-json does not see: perf results
and failed jobs. Prints a JSON array in the same shape, "[]" when there is none.

Usage: auto-found.py [--k6 dir] [--jobs jobs.json]
  --k6 <dir>     k6 summary.json (with the meta.json beside it, searched recursively): one finding per
                 test and metric with a breached threshold. stuck_runs and tracker_events_lost are
                 Critical (a stuck run, lost events), any other threshold is Medium.
  --jobs <file>  GitHub's jobs list of the run: a failed job is a finding, High (Low when cancelled;
                 Medium for a failed compare/memory/leak/p95 step, a regression check). The caller
                 passes it only when nothing more specific was found, so a red job is never dropped.
The key is the test and metric, or the job and failed step, so a new run is the same issue.
"""
import argparse
import json
import pathlib
import re
import sys

CRITICAL_METRICS = {"stuck_runs", "tracker_events_lost"}
REGRESSION_STEP = re.compile(r"compare|memory|leak|p95", re.I)


def k6_findings(root):
    found = {}
    for path in sorted(pathlib.Path(root).rglob("summary.json")):
        try:
            metrics = json.loads(path.read_text())["metrics"]
            meta = json.loads((path.parent / "meta.json").read_text())
        except (OSError, ValueError, KeyError):
            continue
        test = meta.get("test", path.parent.parent.name)
        for name, m in metrics.items():
            breached = [t for t, bad in (m.get("thresholds") or {}).items() if bad]
            if breached:
                found.setdefault((test, name.split("{", 1)[0]), (breached[0], meta))
    return [{
        "key": f"k6:{test}:{metric}", "module": "build",
        "title": f"Perf: {test} breaks {metric} {threshold}",
        "severity": "Critical" if metric in CRITICAL_METRICS else "Medium",
        "detail": f"The k6 `{test}` run on `{meta.get('commit', '?')}` breached `{metric}` `{threshold}`. "
                  "The reports are in the run's artifacts (summary.json, container-stats.csv, server.log).",
        "repro": f"perf/run.sh {test}",
    } for (test, metric), (threshold, meta) in found.items()]


def job_findings(jobs):
    out = []
    for job in jobs:
        if job.get("conclusion") not in ("failure", "timed_out", "cancelled"):
            continue
        steps = [s["name"] for s in job.get("steps") or [] if s.get("conclusion") == "failure"]
        step = steps[0] if steps else job["conclusion"]
        severity = ("Low" if job["conclusion"] == "cancelled"
                    else "Medium" if REGRESSION_STEP.search(step) else "High")
        out.append({
            "key": f"job:{job['name']}:{step}", "module": "build",
            "title": f"CI: {job['name']} fails at {step}", "severity": severity,
            "detail": f"Job [{job['name']}]({job.get('html_url', '')}) {job['conclusion']}"
                      + (f"; failed step: {', '.join(steps)}" if steps else "") + ".",
            "repro": "Re-run the workflow with `workflow_dispatch`, or run the failed step locally.",
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--k6")
    ap.add_argument("--jobs")
    args = ap.parse_args()
    found = k6_findings(args.k6) if args.k6 and pathlib.Path(args.k6).is_dir() else []
    if args.jobs:
        found += job_findings(json.loads(pathlib.Path(args.jobs).read_text()).get("jobs", []))
    print(json.dumps(found))


if __name__ == "__main__":
    sys.exit(main())
