#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Renders the weekly project health report as Markdown.

Usage: project-health.py <dir> [--now <ISO time>]

<dir> holds what project-health.sh fetched:
  issues.json    open issues (REST, slurped pages; pull requests are dropped here)
  project.json   optional: `gh project item-list --format json` of the Yukta Roadmap project
  alerts.json    optional: open code scanning alerts (REST, slurped pages); "null" when not readable

The report is public, so security findings are counted, never listed; the Security tab has them.
"""
import argparse
import collections
import datetime
import json
import pathlib
import sys

TRIAGE_DAYS = 7
IDLE_DAYS = 14
LIST_MAX = 15
SEVERITIES = ("critical", "high", "medium", "low")
MARKER = "<!-- project-health -->"


def load(path, default=None):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def flatten(pages):
    if not pages:
        return []
    return [x for page in pages for x in page] if isinstance(pages[0], list) else pages


def parse_time(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def norm(key):
    return key.lower().replace(" ", "").replace("_", "")


def project_fields(project):
    """issue URL -> {normalised field name: value} for the project's items."""
    fields = {}
    for item in (project or {}).get("items", []):
        url = (item.get("content") or {}).get("url")
        if url:
            fields[url] = {norm(k): v for k, v in item.items() if k not in ("content", "id")}
    return fields


def line(issue, extra=""):
    return f"- [#{issue['number']}]({issue['html_url']}) {issue['title']}{extra}"


def capped(rows):
    if len(rows) > LIST_MAX:
        return rows[:LIST_MAX] + [f"- …and {len(rows) - LIST_MAX} more"]
    return rows


def render(data_dir, now, repo):
    issues = [i for i in flatten(load(data_dir / "issues.json", [])) if "pull_request" not in i]
    project = load(data_dir / "project.json")
    alerts = load(data_dir / "alerts.json")
    fields = project_fields(project)
    labels = {i["number"]: {label["name"] for label in i.get("labels", [])} for i in issues}
    age = {i["number"]: (now - parse_time(i["created_at"])).days for i in issues}
    idle = {i["number"]: (now - parse_time(i["updated_at"])).days for i in issues}
    issues_url = f"https://github.com/{repo}/issues"

    out = [MARKER, f"# Project health, week of {now:%Y-%m-%d}", "",
           "Updated every Monday by `weekly-health.yml`. Edit the project or the issues, "
           "not this report.", ""]

    by_type = collections.Counter((i.get("type") or {}).get("name") or "No type" for i in issues)
    out += ["## Open issues", "", "| Type | Open |", "|---|---|"]
    out += [f"| {t} | {n} |" for t, n in sorted(by_type.items())]
    risks = sum("risk" in labels[i["number"]] for i in issues)
    out += ["", f"{len(issues)} open in all, {risks} of them risks.", ""]

    stale = sorted((i for i in issues
                    if "needs-triage" in labels[i["number"]] and age[i["number"]] > TRIAGE_DAYS),
                   key=lambda i: -age[i["number"]])
    out += [f"## Waiting for triage for more than {TRIAGE_DAYS} days ({len(stale)})", ""]
    out += capped([line(i, f" — {age[i['number']]} days") for i in stale]) or ["None."]
    out.append("")

    if project is None:
        out += ["## Project", "", "The project was not readable (no App token or project number), "
                "so priorities, status and risk ratings are left out.", ""]
    else:
        def field(issue, name):
            return fields.get(issue["html_url"], {}).get(norm(name))

        urgent = [i for i in issues if field(i, "Priority") in ("P0", "P1") and not i.get("assignees")]
        out += [f"## P0 and P1 without an assignee ({len(urgent)})", ""]
        out += capped([line(i, f" — {field(i, 'Priority')}") for i in
                       sorted(urgent, key=lambda i: field(i, "Priority"))]) or ["None."]
        out.append("")
        quiet = [i for i in issues if field(i, "Status") == "In progress" and idle[i["number"]] > IDLE_DAYS]
        out += [f"## In progress, no activity for more than {IDLE_DAYS} days ({len(quiet)})", ""]
        out += capped([line(i, f" — {idle[i['number']]} days") for i in
                       sorted(quiet, key=lambda i: -idle[i["number"]])]) or ["None."]
        out.append("")

        def rating(issue, name):
            value = field(issue, name)
            return int(value) if isinstance(value, (int, float)) else None

        high = [i for i in issues if "risk" in labels[i["number"]] and (rating(i, "Impact") or 0) >= 4]
        high.sort(key=lambda i: (-(rating(i, "Impact") or 0), rating(i, "Effort") or 9))
        out += [f"## Open risks with impact 4 or 5 ({len(high)})", ""]
        out += capped([line(i, f" — impact {rating(i, 'Impact')}, effort {rating(i, 'Effort') or '?'}")
                       for i in high]) or ["None."]
        out.append("")

    out += ["## Code scanning", ""]
    if alerts is None:
        out += ["Alerts were not readable by this run.", ""]
    else:
        alerts = flatten(alerts)
        level = collections.Counter(
            ((a.get("rule") or {}).get("security_severity_level") or "none") for a in alerts)
        tools = collections.Counter((a.get("tool") or {}).get("name", "?") for a in alerts)
        counts = ", ".join(f"{level[s]} {s}" for s in SEVERITIES)
        out += [f"{len(alerts)} open alerts: {counts}"
                + (f", {level['none']} without a security rating" if level["none"] else "") + ".",
                "By tool: " + (", ".join(f"{t} {n}" for t, n in sorted(tools.items())) or "none") + ".",
                f"Details are in the [Security tab](https://github.com/{repo}/security/code-scanning) "
                "(maintainers only).", ""]

    guide = f"https://github.com/{repo}/blob/main/.github/PROJECT.md"
    out += [f"[All open issues]({issues_url}) · [Project guide]({guide})"]
    return "\n".join(out) + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dir", type=pathlib.Path)
    parser.add_argument("--repo", default="infenia/yukta")
    parser.add_argument("--now", help="ISO time to report as of (tests)")
    args = parser.parse_args(argv)
    now = parse_time(args.now) if args.now else datetime.datetime.now(datetime.timezone.utc)
    sys.stdout.write(render(args.dir, now, args.repo))


if __name__ == "__main__":
    main(sys.argv[1:])
