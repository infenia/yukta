#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Renders the weekly project health report as Markdown.

Usage: project-health.py <dir> [--now <ISO time>]
       project-health.py --workflows            the workflow files whose last scheduled run it needs

<dir> holds what project-health.sh fetched:
  issues.json    open issues (REST, slurped pages; pull requests are dropped here)
  project.json   optional: `gh project item-list --format json` of the Yukta Roadmap project
  alerts.json    optional: open code scanning alerts (REST, slurped pages); "null" when not readable
  runs.json      optional: {workflow file: last scheduled run on main | "none" | "missing"}, where a run
                 is {conclusion, created_at, html_url}; "none" = never ran, "missing" = no such workflow

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
REVIEW_DAYS = 7
LIST_MAX = 15
SEVERITIES = ("critical", "high", "medium", "low")
MARKER = "<!-- project-health -->"
# The jobs that decide whether Yukta is robust right now: (name, workflow file, longest age in days
# of a run that still counts). A missing job is "not configured", never green.
REQUIRED_RUNS = (("Nightly build and tests", "ci.yml", 2),
                 ("Weekly soak", "perf-weekly.yml", 8),
                 ("Mutation score ratchet", "mutation.yml", 8))
OTHER_RUNS = (("Examples", "examples-nightly.yml", 2),
              ("Nightly perf", "perf-nightly.yml", 2),
              ("Native smoke", "native-smoke.yml", 2))
BLOCKING = ("Critical", "High")


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



def robustness(runs, issues, labels, field, now):
    """The "robust right now" section: a verdict and the evidence for it."""
    reasons, rows = [], []
    for required, group in ((True, REQUIRED_RUNS), (False, OTHER_RUNS)):
        for name, workflow, max_days in group:
            run = (runs or {}).get(workflow)
            if runs is None:
                state, when = "unknown", "-"
                reasons += [f"{name}: the scheduled runs were not readable"] if required else []
            elif run == "missing" or run is None:
                state, when = "not configured", "-"
                reasons += [f"{name}: not configured"] if required else []
            elif run == "none":
                state, when = "no scheduled run yet", "-"
                reasons += [f"{name}: no scheduled run yet"] if required else []
            else:
                days = (now - parse_time(run["created_at"])).days
                state = run.get("conclusion") or "running"
                when = f"[{days} days ago]({run.get('html_url', '')})"
                if state not in ("success", "running"):
                    reasons.append(f"{name}: {state}")
                elif days > max_days and required:
                    reasons.append(f"{name}: last run {days} days ago (more than {max_days})")
            rows.append(f"| {name} | {state} | {when} |")

    found = [i for i in issues if "auto-found" in labels[i["number"]]]
    # Without the project the severity is unknown, and an unknown one is not assumed harmless.
    blockers = [i for i in found if field is None or field(i, "Severity") in BLOCKING]
    if blockers:
        reasons.append(f"{len(blockers)} open auto-found issue(s) of severity "
                       + ("Critical or High" if field else "not known"))

    out = ["## Robustness right now", "",
           ("**ROBUST**" if not reasons else "**NOT ROBUST**") + " — "
           + ("no open blocker, and the nightly build, the weekly soak and the mutation ratchet "
              "are green." if not reasons else "; ".join(reasons) + "."), "",
           "| Scheduled job | Last run on main | When |", "|---|---|---|", *rows, ""]
    out += [f"Open `auto-found` issues: {len(found)}, {len(blockers)} blocking."]
    out += capped([line(i) for i in blockers])
    out.append("")
    return out


def render(data_dir, now, repo):
    everything = flatten(load(data_dir / "issues.json", []))
    issues = [i for i in everything if "pull_request" not in i]
    project = load(data_dir / "project.json")
    alerts = load(data_dir / "alerts.json")
    runs = load(data_dir / "runs.json")
    fields = project_fields(project)
    labels = {i["number"]: {label["name"] for label in i.get("labels", [])} for i in issues}
    age = {i["number"]: (now - parse_time(i["created_at"])).days for i in issues}
    idle = {i["number"]: (now - parse_time(i["updated_at"])).days for i in everything}
    issues_url = f"https://github.com/{repo}/issues"

    out = [MARKER, f"# Project health, week of {now:%Y-%m-%d}", "",
           ("Updated every Monday by `weekly-health.yml`. Edit the project or the issues, "
            "not this report."), ""]

    def severity(issue, name):
        return fields.get(issue["html_url"], {}).get(norm(name))

    out += robustness(runs, issues, labels, severity if project is not None else None, now)

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
        out += ["## Project", "", ("The project was not readable (no App token or project number), "
                                   "so priorities, status and risk ratings are left out."), ""]
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

        waiting = [i for i in everything
                   if field(i, "Status") == "In review" and idle[i["number"]] > REVIEW_DAYS]
        out += [f"## In review, no activity for more than {REVIEW_DAYS} days ({len(waiting)})", ""]
        out += capped([line(i, f" — {idle[i['number']]} days") for i in
                       sorted(waiting, key=lambda i: -idle[i["number"]])]) or ["None."]
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
                (f"Details are in the [Security tab](https://github.com/{repo}/security/code-scanning) "
                 "(maintainers only)."), ""]

    guide = f"https://github.com/{repo}/blob/main/.github/PROJECT.md"
    out += [f"[All open issues]({issues_url}) · [Project guide]({guide})"]
    return "\n".join(out) + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dir", type=pathlib.Path, nargs="?")
    parser.add_argument("--workflows", action="store_true",
                        help="print the workflow files whose last scheduled run the report needs")
    parser.add_argument("--repo", default="infenia/yukta")
    parser.add_argument("--now", help="ISO time to report as of (tests)")
    args = parser.parse_args(argv)
    if args.workflows:
        print(*(w for _, w, _ in REQUIRED_RUNS + OTHER_RUNS))
        return
    now = parse_time(args.now) if args.now else datetime.datetime.now(datetime.timezone.utc)
    sys.stdout.write(render(args.dir, now, args.repo))


if __name__ == "__main__":
    main(sys.argv[1:])
