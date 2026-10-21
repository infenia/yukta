#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Keeps the repository labels in line with .github/labels.yml.

Usage: sync-labels.py --check            validate labels.yml and that every label the automation
                                         uses (labeler, dependabot, stale, issue forms) is in it
       sync-labels.py [--apply] <repo>   show (or, with --apply, make) the changes on GitHub

The sync creates and updates labels, never deletes them: a label that is missing from the file
is only reported. Needs PyYAML (pinned in .github/requirements/yamllint.txt) and, for a sync,
the gh CLI with GH_TOKEN allowed to write issues.
"""
import json
import pathlib
import re
import subprocess
import sys
import urllib.parse

import yaml

GITHUB = pathlib.Path(__file__).resolve().parent.parent
LABELS = GITHUB / "labels.yml"
COLOR = re.compile(r"^[0-9a-f]{6}$")
MAX_DESCRIPTION = 100


def load_labels():
    with LABELS.open(encoding="utf-8") as f:
        return yaml.safe_load(f) or []


def problems_in(labels):
    found, seen = [], set()
    for i, label in enumerate(labels):
        name = str(label.get("name", "")).strip()
        if not name:
            found.append(f"entry {i}: no name")
            continue
        if name.lower() in seen:
            found.append(f"{name}: listed twice")
        seen.add(name.lower())
        if not COLOR.match(str(label.get("color", ""))):
            found.append(f"{name}: color must be 6 lower-case hex digits, got {label.get('color')!r}")
        if len(str(label.get("description", ""))) > MAX_DESCRIPTION:
            found.append(f"{name}: description longer than {MAX_DESCRIPTION} characters")
    return found


def used_labels():
    """(label, where) for every label the repository's automation applies."""
    used = []
    labeler = yaml.safe_load((GITHUB / "labeler.yml").read_text(encoding="utf-8")) or {}
    used += [(name, "labeler.yml") for name in labeler]
    dependabot = yaml.safe_load((GITHUB / "dependabot.yml").read_text(encoding="utf-8")) or {}
    for update in dependabot.get("updates", []):
        used += [(name, "dependabot.yml") for name in update.get("labels", [])]
    stale = yaml.safe_load((GITHUB / "workflows" / "stale.yml").read_text(encoding="utf-8"))
    for step in stale["jobs"]["stale"]["steps"]:
        for key in ("stale-issue-label", "stale-pr-label", "exempt-issue-labels", "exempt-pr-labels"):
            value = step.get("with", {}).get(key)
            if value:
                used += [(name.strip(), f"stale.yml {key}") for name in value.split(",")]
    for form in sorted((GITHUB / "ISSUE_TEMPLATE").glob("*.yml")):
        data = yaml.safe_load(form.read_text(encoding="utf-8")) or {}
        used += [(name, f"ISSUE_TEMPLATE/{form.name}") for name in data.get("labels", [])]
    return used


def check():
    labels = load_labels()
    found = problems_in(labels)
    names = {str(label.get("name")).lower() for label in labels}
    found += [f"{name}: used by {where} but not in labels.yml"
              for name, where in used_labels() if name.lower() not in names]
    for problem in found:
        print(f"labels.yml: {problem}", file=sys.stderr)
    if not found:
        print(f"labels.yml: {len(labels)} labels, every label in use is defined")
    return 1 if found else 0


def gh(*args, body=None):
    result = subprocess.run(["gh", "api", *args], input=None if body is None else json.dumps(body),
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def sync(repo, apply):
    labels = load_labels()
    if problems_in(labels):
        return check()
    existing = {}
    for page in gh("--paginate", "--slurp", f"repos/{repo}/labels?per_page=100"):
        for label in page:
            existing[label["name"].lower()] = label
    changes = 0
    for label in labels:
        want = {"name": str(label["name"]), "color": str(label["color"]),
                "description": str(label.get("description", ""))}
        have = existing.pop(want["name"].lower(), None)
        if have is None:
            action, args = "create", ["-X", "POST", f"repos/{repo}/labels"]
        elif (have["name"], have["color"].lower(), have.get("description") or "") != (
                want["name"], want["color"], want["description"]):
            action = "update"
            path = urllib.parse.quote(have["name"], safe="")
            args = ["-X", "PATCH", f"repos/{repo}/labels/{path}"]
            want["new_name"] = want.pop("name")
        else:
            continue
        changes += 1
        print(f"{action}: {label['name']}")
        if apply:
            gh(*args, "--input", "-", body=want)
    for name in sorted(label["name"] for label in existing.values()):
        print(f"not in labels.yml (left as is): {name}")
    print(f"{changes} change(s){'' if apply else ' (dry run; pass --apply to make them)'}")
    return 0


def main(argv):
    if argv == ["--check"]:
        return check()
    apply = "--apply" in argv
    rest = [arg for arg in argv if arg != "--apply"]
    if len(rest) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    return sync(rest[0], apply)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
