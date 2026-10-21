#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Prints the failed tests found in JUnit XML reports as a Markdown list (nothing if none failed).

Usage: junit-failures.py <dir> [limit]   -- searches <dir> recursively for TEST-*.xml
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

MAX_LINES = 4


def failures(root):
    for path in sorted(pathlib.Path(root).rglob("TEST-*.xml")):
        try:
            tree = ET.parse(path)
        except ET.ParseError as e:
            print(f"warning: skipping unreadable {path}: {e}", file=sys.stderr)
            continue
        for case in tree.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            detail = (problem.get("message") or problem.text or "").strip()
            yield case.get("classname", "?"), case.get("name", "?"), detail


def main():
    root = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    found = list(failures(root))
    if not found:
        return
    print(f"#### Failed tests ({len(found)})\n")
    for cls, name, detail in found[:limit]:
        print(f"- `{cls}` — `{name}`")
        lines = detail.replace("```", "'''").splitlines()[:MAX_LINES]
        if lines:
            print("  ```text")
            for line in lines:
                print(f"  {line[:200]}")
            print("  ```")
    if len(found) > limit:
        print(f"\n…and {len(found) - limit} more (see the run's build-reports artifacts).")


if __name__ == "__main__":
    main()
