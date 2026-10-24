#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Summarizes JUnit XML reports (TEST-*.xml, searched recursively) as Markdown.

Usage: junit-failures.py <dir> [limit]       failed and flaky tests (nothing if all passed)
       junit-failures.py --durations <dir>   test time per shard and the slowest test classes
       junit-failures.py --flaky-json <dir>  flaky tests as JSON: [{class, name, detail, module}]

A test that failed and then passed (the nightly run retries with -PretryFlakyTests) is flaky;
one that never passed is broken. Shards are the first directory under <dir>, i.e. the
build-reports-<shard> artifact names.
"""
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

MAX_LINES = 4


def reports(root):
    for path in sorted(pathlib.Path(root).rglob("TEST-*.xml")):
        try:
            yield path, ET.parse(path).getroot()
        except ET.ParseError as e:
            print(f"warning: skipping unreadable {path}: {e}", file=sys.stderr)


def module_of(path, root):
    """The Module field value for a report path such as
    <root>/build-reports-plugins/plugins/processors/llm-call/build/test-results/test/TEST-x.xml."""
    parts = path.relative_to(root).parts[1:]
    project = parts[:parts.index("build")] if "build" in parts else parts[:1]
    if not project:
        return None
    if project[0] == "plus" and len(project) > 1:
        return project[1]
    return project[0]


def outcomes(root):
    """(class, test) -> {"failed": [detail, ...], "passed": bool, "module": str}, in report order."""
    tests = {}
    for path, suite in reports(root):
        for case in suite.iter("testcase"):
            key = (case.get("classname", "?"), case.get("name", "?"))
            entry = tests.setdefault(key, {"failed": [], "types": [], "passed": False,
                                           "module": module_of(path, pathlib.Path(root))})
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                entry["failed"].append((problem.get("message") or problem.text or "").strip())
                entry["types"].append(problem.get("type") or "")
            elif case.find("skipped") is None:
                entry["passed"] = True
    return tests


def print_tests(heading, found, limit):
    print(f"#### {heading} ({len(found)})\n")
    for (cls, name), detail in found[:limit]:
        print(f"- `{cls}` — `{name}`")
        lines = detail.replace("```", "'''").splitlines()[:MAX_LINES]
        if lines:
            print("  ```text")
            for line in lines:
                print(f"  {line[:200]}")
            print("  ```")
    if len(found) > limit:
        print(f"\n…and {len(found) - limit} more (see the run's build-reports artifacts).")
    print()


def failures(root, limit):
    tests = outcomes(root)
    broken = [(k, v["failed"][0]) for k, v in tests.items() if v["failed"] and not v["passed"]]
    flaky = [(k, v["failed"][0]) for k, v in tests.items() if v["failed"] and v["passed"]]
    if broken:
        print_tests("Failed tests", broken, limit)
    if flaky:
        print_tests("Flaky tests — failed, then passed on retry", flaky, limit)


def durations(root, top=15):
    base = len(pathlib.Path(root).parts)
    per_shard = collections.Counter()
    per_class = collections.Counter()
    for path, suite in reports(root):
        seconds = float(suite.get("time") or 0)
        shard = path.parts[base].removeprefix("build-reports-")
        per_shard[shard] += seconds
        per_class[(shard, suite.get("name", "?"))] += seconds
    if not per_shard:
        return
    print("#### Test time per shard\n")
    print("| Shard | Test time (summed over forks) |")
    print("|-------|------|")
    for shard, seconds in per_shard.most_common():
        print(f"| {shard} | {seconds:.0f}s |")
    print(f"\n#### Slowest test classes (top {top})\n")
    print("| Shard | Class | Time |")
    print("|-------|-------|------|")
    for (shard, cls), seconds in per_class.most_common(top):
        print(f"| {shard} | `{cls}` | {seconds:.1f}s |")
    print()


def flaky_json(root):
    tests = outcomes(root)
    print(json.dumps([{"class": cls, "name": name, "detail": v["failed"][0][:2000], "module": v["module"]}
                      for (cls, name), v in tests.items() if v["failed"] and v["passed"]]))


def main():
    if sys.argv[1] == "--durations":
        durations(sys.argv[2])
    elif sys.argv[1] == "--flaky-json":
        flaky_json(sys.argv[2])
    else:
        failures(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 50)


if __name__ == "__main__":
    main()
