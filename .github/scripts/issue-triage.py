#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Reads an `issues` event and prints what triage should set, as JSON:

  {"labels": ["core"], "fields": ["Status=Triage", "Module=core", "Kind=Defect", ...]}

Usage: issue-triage.py <event.json>     (in Actions: "$GITHUB_EVENT_PATH")

The answers come from the issue form (.github/ISSUE_TEMPLATE): an issue form body is a list of
"### <label>" sections. The Module answer becomes the area label and the Module field; Severity,
Priority, Category, Impact and Effort become fields. Kind follows from the labels and the type.
An issue not opened from a form gets only Status and Kind. The body is user input: it is only
matched against fixed option lists here, never run or interpolated.
"""
import json
import re
import sys

MODULES = {"boot", "build", "cli", "core", "mcp", "messaging", "plugin-api", "plugins", "plus",
           "persistence-postgres", "workers", "ui", "web", "documentation"}
SEVERITIES = {"Critical", "High", "Medium", "Low"}
PRIORITIES = {"P0", "P1", "P2", "P3"}
CATEGORIES = {"Security", "Correctness", "Reliability", "Availability", "Performance",
              "Scalability", "Evolvability", "Operability", "Governance"}
NO_ANSWER = "_No response_"


def form_answers(body):
    """{section label: answer} for an issue form body."""
    answers = {}
    for section in re.split(r"^### ", body or "", flags=re.M)[1:]:
        label, _, answer = section.partition("\n")
        answer = answer.strip()
        if answer and answer != NO_ANSWER:
            answers[label.strip()] = answer
    return answers


def first_word(answer):
    """The option itself, without the explanation in brackets: "High (a feature ...)" -> "High"."""
    return answer.split(" (", 1)[0].strip()


def kind(labels, type_name):
    if "risk" in labels:
        return "Risk"
    if "dogfood" in labels:
        return "Dogfood"
    if "ci-nightly" in labels:
        return "CI failure"
    if "flaky-test" in labels:
        return "Flaky test"
    if "perf" in labels:
        return "Perf"
    return {"Bug": "Defect", "Feature": "Improvement"}.get(type_name)


def triage(event):
    issue = event["issue"]
    labels = {label["name"] for label in issue.get("labels", [])}
    type_name = (issue.get("type") or {}).get("name")
    answers = form_answers(issue.get("body"))
    add_labels, fields = [], ["Status=Triage"]

    module = first_word(answers.get("Module", ""))
    if module in MODULES:
        add_labels.append(module)
        fields.append(f"Module={module}")
    checks = {"Severity": SEVERITIES, "Priority": PRIORITIES, "Category": CATEGORIES}
    for name, allowed in checks.items():
        value = first_word(answers.get(name, ""))
        if value in allowed:
            fields.append(f"{name}={value}")
    for name in ("Impact", "Effort"):
        value = answers.get(name, "")
        if value in {"1", "2", "3", "4", "5"}:
            fields.append(f"{name}={value}")
    if answers.get("Category") == "Security" and "security" not in labels:
        add_labels.append("security")
    issue_kind = kind(labels, type_name)
    if issue_kind:
        fields.append(f"Kind={issue_kind}")
    return {"labels": [label for label in add_labels if label not in labels], "fields": fields}


def main(argv):
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[0], encoding="utf-8") as f:
        print(json.dumps(triage(json.load(f))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
