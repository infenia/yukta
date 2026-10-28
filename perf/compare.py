#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Compares a perf report with the baseline and names what got worse.

Usage: compare.py <report dir> [--baseline perf/baseline.json] [--findings FILE] [--write-baseline FILE]

<report dir> is what perf/run.sh wrote (summary.json, container-stats.csv). Checks:
  * stuck runs and lost tracker events: none, ever;
  * success rate: at least 99%, overall and per workflow;
  * latency per workflow against the baseline: the median may grow by 25% and the p99 by 30%, each
    plus 25 ms, because the test polls every 50 ms. The p95 is not checked: across identical runs it
    moved by up to 13% (BASELINE.md) while the median moved by 2 ms, and the p99 already covers the
    tail, so a third latency gate would only add false alarms;
  * memory, for a run of 15 minutes or more: the mean of the last tenth of the run may not exceed the
    mean of the window from 20% to 30% by more than 150 MiB (a JVM grows for a while, then levels
    off; a leak keeps going).
With --findings the failures are also written as a JSON array for .github/scripts/auto-found.sh.
With --write-baseline this run's median and p99 per workflow are written in the baseline format, so a
scheduled job can keep the last good run as the next baseline.
Exit status 1 when a check fails.
"""
import argparse
import json
import pathlib
import re
import sys

MEDIAN_GROWTH, P99_GROWTH, SLACK_MS = 1.25, 1.30, 25
MIN_SUCCESS = 0.99
MEMORY_GROWTH_MIB = 150
MEMORY_MIN_SECONDS = 15 * 60


def metric(summary, name, *keys):
    """The first of keys present in the k6 metric `name`, or None."""
    values = summary.get("metrics", {}).get(name) or {}
    for key in keys:
        if key in values:
            return values[key]
    return None


def memory_mib(path):
    """[(seconds since the first sample, MiB)] from container-stats.csv (time,cpu,mem)."""
    rows = []
    for line in path.read_text().splitlines()[1:]:
        parts = line.split(",")
        used = re.match(r"([\d.]+)(MiB|GiB)", parts[2]) if len(parts) > 2 else None
        clock = re.match(r"(\d+):(\d+):(\d+)", parts[0])
        if used and clock:
            h, m, s = map(int, clock.groups())
            rows.append((h * 3600 + m * 60 + s, float(used.group(1)) * (1024 if used.group(2) == "GiB" else 1)))
    if rows:
        start = rows[0][0]
        day, last = 0, start
        out = []
        for seconds, mib in rows:
            day += 86400 if seconds < last else 0  # the clock passed midnight
            last = seconds
            out.append((seconds + day - start, mib))
        return out
    return []


def check(report, baseline):
    """[(key, severity, title, detail)] for every failed check."""
    summary = json.loads((report / "summary.json").read_text())
    found = []
    for name, label in (("stuck_runs", "runs that never finished"), ("teardown_errors", "stuck-run checks that could not look"),
                        ("tracker_events_lost", "lost tracker events")):
        count = metric(summary, name, "count", "value")
        if count:
            found.append((f"perf:{name}", "Critical", f"Perf: {label}",
                          f"{name} = {count:g}; the test requires 0."))
    rate = metric(summary, "workflow_success", "rate", "value")
    if rate is not None and rate < MIN_SUCCESS:
        found.append(("perf:success", "High", "Perf: workflow success rate below 99%",
                      f"workflow_success = {rate:.2%}."))
    for workflow, base in sorted(baseline.items()):
        tag = f"{{wf:{workflow}}}"
        wf_rate = metric(summary, "workflow_success" + tag, "rate", "value")
        if wf_rate is not None and wf_rate < MIN_SUCCESS:
            found.append((f"perf:success:{workflow}", "High", f"Perf: {workflow} success rate below 99%",
                          f"{workflow}: {wf_rate:.2%}."))
        median, p99 = (metric(summary, "workflow_e2e" + tag, key) for key in ("med", "p(99)"))
        if median is not None and median > base["p50"] * MEDIAN_GROWTH + SLACK_MS:
            found.append((f"perf:p50:{workflow}", "Medium", f"Perf: {workflow} median latency regressed",
                          f"median {median:.0f} ms against a baseline of {base['p50']} ms "
                          f"(limit {base['p50'] * MEDIAN_GROWTH + SLACK_MS:.0f} ms)."))
        if p99 is not None and p99 > base["p99"] * P99_GROWTH + SLACK_MS:
            found.append((f"perf:p99:{workflow}", "Medium", f"Perf: {workflow} p99 latency regressed",
                          f"p99 {p99:.0f} ms against a baseline of {base['p99']} ms "
                          f"(limit {base['p99'] * P99_GROWTH + SLACK_MS:.0f} ms)."))
    stats = report / "container-stats.csv"
    samples = memory_mib(stats) if stats.exists() else []
    if samples and samples[-1][0] >= MEMORY_MIN_SECONDS:
        total = samples[-1][0]
        early = [m for s, m in samples if 0.2 * total <= s <= 0.3 * total]
        late = [m for s, m in samples if s >= 0.9 * total]
        if early and late:
            growth = sum(late) / len(late) - sum(early) / len(early)
            if growth > MEMORY_GROWTH_MIB:
                found.append(("perf:memory", "Medium", "Perf: memory keeps growing during the soak",
                              f"container memory grew {growth:.0f} MiB between 20-30% and the last tenth "
                              f"of a {total / 60:.0f} minute run (limit {MEMORY_GROWTH_MIB} MiB)."))
    return found


def this_run(report):
    """{workflow: {p50, p99}} measured in the report, in the format of baseline.json."""
    summary = json.loads((report / "summary.json").read_text())
    out = {}
    for name, values in summary.get("metrics", {}).items():
        match = re.fullmatch(r"workflow_e2e\{wf:([^}]+)\}", name)
        if match and "med" in values and "p(99)" in values:
            out[match.group(1)] = {"p50": round(values["med"]), "p99": round(values["p(99)"])}
    return out


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=pathlib.Path)
    parser.add_argument("--baseline", type=pathlib.Path, default=pathlib.Path("perf/baseline.json"))
    parser.add_argument("--findings", type=pathlib.Path)
    parser.add_argument("--write-baseline", type=pathlib.Path)
    args = parser.parse_args(argv)
    found = check(args.report, json.loads(args.baseline.read_text()))
    for _, severity, title, detail in found:
        print(f"FAIL [{severity}] {title}: {detail}")
    if not found:
        print("perf: no regression against the baseline")
    if args.write_baseline:
        args.write_baseline.write_text(json.dumps(this_run(args.report), indent=2) + "\n")
    if args.findings:
        args.findings.write_text(json.dumps([
            {"key": key, "title": title, "severity": severity, "module": "core", "detail": detail,
             "repro": "perf/run.sh soak"} for key, severity, title, detail in found]))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
