#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit init ISSUE | --ticket-file F [--root R] [--state-dir D] [--base REF] [--no-worktree]

Creates .yukta-dev/ticket/<run_id>/ (design section 4), branch issue/<n> and worktree ../yukta-issue-<n>.
Prints RUN_DIR=... and WORKTREE=...; prints SKIPPED <why> and exits 3 when the run must not start (no acceptance criteria, branch or
worktree exists): no tokens are spent. Only the acceptance criteria count as requirements; the rest of the issue is data."""
import argparse, datetime, json, os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, die, git, load, save

CONVENTIONAL = {"feat": "feature", "fix": "bug", "refactor": "refactor", "docs": "docs", "chore": "chore", "ci": "chore", "build": "chore",
                "style": "chore", "perf": "chore", "revert": "chore", "test": "chore"}
TYPE_LABEL = {"type/feature": "feature", "type/bugfix": "bug", "type/refactor": "refactor", "type/docs": "docs", "type/chore": "chore", "type/test": "chore"}


def kind_of(title, labels):
    for l in labels:
        if l in TYPE_LABEL:
            return TYPE_LABEL[l]
    m = re.match(r"(\w+)(\([^)]*\))?!?:", title)
    return CONVENTIONAL.get(m.group(1), "feature") if m else "feature"


def criteria(body):
    """The list items under an 'Acceptance criteria' heading, up to the next heading."""
    out, on = [], False
    for line in (body or "").splitlines():
        if re.match(r"\s{0,3}#{1,6}\s", line):
            on = bool(re.search(r"acceptance criteria", line, re.I))
            continue
        m = re.match(r"\s*(?:[-*]|\d+[.)])\s+(?:\[[ xX]\]\s*)?(.+)", line)
        if on and m:
            out.append(m.group(1).strip())
    return out


def main():
    a = argparse.ArgumentParser()
    a.add_argument("issue", nargs="?")
    a.add_argument("--ticket-file")
    a.add_argument("--root", default=None)
    a.add_argument("--state-dir", default=None)
    a.add_argument("--base", default="origin/main")
    a.add_argument("--no-worktree", action="store_true")
    a.add_argument("--run-id", help="use this run id (the workflow computes it first so every node knows the run dir)")
    o = a.parse_args()
    if bool(o.issue) == bool(o.ticket_file):
        die("usage: ticketkit init ISSUE | --ticket-file F")
    root = pathlib.Path(o.root or git("rev-parse", "--show-toplevel").stdout.strip()).resolve()
    if o.ticket_file:
        raw = load(o.ticket_file)
    else:
        r = subprocess.run(["gh", "issue", "view", o.issue, "--json", "number,title,body,labels"], capture_output=True, text=True)
        if r.returncode:
            die(f"gh issue view {o.issue}: {r.stderr.strip()}")
        raw = json.loads(r.stdout)
    labels = [l["name"] if isinstance(l, dict) else l for l in raw.get("labels", [])]
    project = load(KIT / "project.json")
    ac = criteria(raw.get("body"))
    n = str(raw["number"])
    if not ac:
        print("SKIPPED no acceptance criteria"); sys.exit(3)
    branch, wt = project["git"]["branch"].format(n=n), (root / project["git"]["worktree"].format(n=n)).resolve()
    if git("rev-parse", "--verify", "-q", branch, cwd=root, check=False).returncode == 0 or wt.exists():
        print(f"SKIPPED branch {branch} or worktree {wt} exists"); sys.exit(3)
    run_id = o.run_id or f"{n}-{datetime.datetime.now(datetime.timezone.utc):%Y%m%dT%H%M%SZ}"
    state = pathlib.Path(o.state_dir or os.environ.get("YUKTA_DEV_STATE_DIR") or root / ".yukta-dev")
    base_id, k = run_id, 1
    if o.run_id and (state / "ticket" / run_id).exists():
        die(f"run {run_id} exists")
    while (state / "ticket" / run_id).exists():  # two runs of one issue in the same second
        k += 1
        run_id = f"{base_id}-{k}"
    run = state / "ticket" / run_id
    for d in ("spec", "steps", "review"):
        (run / d).mkdir(parents=True)
    modules = sorted({l for l in labels if l in project["module_labels"]})
    save(run / "ticket.json", {"ticket_id": n, "title": raw["title"], "kind": kind_of(raw["title"], labels), "labels": labels, "modules": modules,
                               "acceptance_criteria": ac, "body": raw.get("body") or "",
                               "allow_guard_paths": bool({"build", "dogfood"} & set(labels))})
    save(run / "project.json", project)
    (run / "events.jsonl").touch()
    if not o.no_worktree:
        git("worktree", "add", "-q", "-b", branch, str(wt), o.base, cwd=root)
        nm = root / "ui" / "node_modules"
        if nm.is_dir() and not (wt / "ui" / "node_modules").exists():
            (wt / "ui" / "node_modules").symlink_to(nm)
    print(f"RUN_DIR={run}\nWORKTREE={wt}\nRUN_ID={run_id}")


if __name__ == "__main__":
    main()
