#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit github ACTION ... : the GitHub side of the pipeline (design 15.9). It reuses pickup's rules and .github/scripts/project-lib.sh.

  intake  ISSUE               open, assigned to the user, Ready on the project, acceptance criteria. SKIPPED (exit 3) if not; BLOCKED (exit 4) when an
                              acceptance criterion asks for secrets, a push to main, other repos, changed settings or skipped checks. Text outside the
                              criteria that asks for such things is data and ignored (counted in the output)
  claim   ISSUE [STATUS]      move the issue on the project (default "In progress")
  plan-comment RUN            post the spec summary (objective, steps, case table) as an issue comment
  dead-end RUN --stage S --reason R   issue back to Ready and a comment: what blocked it and what was tried; the worktree stays
  finish  RUN --worktree W    spotlessApply, commit, push issue/<n>, open a DRAFT PR (conventional title, `Fixes #n`, the RESULT line, spec summary,
                              cost by role); fails when the body lacks what project.json pr_body_must_contain names. Never merges, never pushes main

Env: YUKTA_PICKUP_REPO (infenia/yukta), YUKTA_PICKUP_PROJECT (5), YUKTA_PICKUP_OWNER (infenia), TICKET_FORMAT (override of the format command)."""
import argparse, json, os, pathlib, re, subprocess, sys, tempfile
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from init import criteria
from ticketlib import KIT, cost_by_role, die, git, load

REPO = os.environ.get("YUKTA_PICKUP_REPO", "infenia/yukta")
PROJECT = os.environ.get("YUKTA_PICKUP_PROJECT", "5")
OWNER = os.environ.get("YUKTA_PICKUP_OWNER", "infenia")
TYPES = {"feature": "feat", "bug": "fix", "refactor": "refactor", "docs": "docs", "chore": "chore"}
CONVENTIONAL = re.compile(r"^(feat|fix|docs|refactor|test|chore|style|ci|build|perf|revert)(\([^)]*\))?!?: \S")
SECRET = r"(api[ _-]?keys?|secrets?|tokens?|passwords?|credentials?)"
FORBIDDEN = [
    (re.compile(rf"\b(print|show|echo|send|post|reveal|leak|output|exfiltrate|dump)\b.*\b{SECRET}|\b{SECRET}\b.*\b(print|show|echo|send|reveal|leak|exfiltrate|dump)\b", re.I), "asks for secrets"),
    (re.compile(r"\b(force[- ])?push\w*\b.*\b(to|on|into)\s+(origin/)?(main|master)\b|\bmerge\b.*\b(the pr|pull request|into main)\b", re.I), "asks to push to main or merge"),
    (re.compile(r"\b(other|another|different) repo(sitory)?\b|github\.com/(?!infenia/yukta)", re.I), "names another repository"),
    (re.compile(r"\b(change|modify|edit|disable|update|weaken)\b.*\b(settings|permissions|branch protection|codeowners|coverage threshold)", re.I), "asks to change settings"),
    (re.compile(r"\b(skip|bypass|disable|ignore)\w*\b.*\b(checks?|tests?|gates?|hooks?|ci|coverage)\b|--no-verify|dangerously-skip-permissions", re.I), "asks to skip checks"),
]


def gh(*args, check=True):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and r.returncode:
        die(f"gh {' '.join(args[:3])}: {r.stderr.strip()[:300]}")
    return r.stdout


def intake(n):
    v = json.loads(gh("issue", "view", n, "-R", REPO, "--json", "number,state,assignees,title,body,labels,url"))
    me = gh("api", "user", "-q", ".login").strip()
    items = json.loads(gh("project", "item-list", PROJECT, "--owner", OWNER, "--limit", "5000", "--format", "json")).get("items", [])
    ready = any(str((i.get("content") or {}).get("number")) == str(n) and i.get("status") == "Ready" for i in items)
    if v["state"].upper() != "OPEN":
        print("SKIPPED the issue is not open"); return 3
    if me not in [a["login"] for a in v["assignees"]]:
        print(f"SKIPPED the issue is not assigned to {me}"); return 3
    if not ready:
        print(f"SKIPPED the issue is not Ready on project {PROJECT}"); return 3
    ac = criteria(v.get("body"))
    if not ac:
        print("SKIPPED no acceptance criteria"); return 3
    for item in ac:
        for rx, why in FORBIDDEN:
            if rx.search(item):
                print(f"BLOCKED an acceptance criterion {why}: {item[:120]}"); return 4
    rest = (v.get("body") or "").replace("\n".join(ac), "")
    ignored = sum(1 for rx, _ in FORBIDDEN if rx.search(rest))
    print(f"ELIGIBLE criteria={len(ac)} ignored_body_instructions={ignored}")
    return 0


def set_status(n, status):
    url = gh("issue", "view", n, "-R", REPO, "--json", "url", "-q", ".url").strip()
    env = dict(os.environ, PROJECT_OWNER=OWNER, PROJECT_NUMBER=PROJECT, PROJECT_TOKEN=gh("auth", "token").strip())
    lib = KIT.parent.parent / ".github" / "scripts" / "project-lib.sh"  # the library of this checkout, whatever the run's repo
    r = subprocess.run(["bash", "-c", '. "$3"; project_add_item "$1" Status="$2"', "x", url, status, str(lib)], capture_output=True, text=True, env=env)
    if r.returncode:
        die(f"project status {status}: {r.stderr.strip()[:300]}")
    print(f"STATUS #{n} {status}")


def comment(n, body):
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
        f.write(body)
    gh("issue", "comment", n, "-R", REPO, "-F", f.name)
    os.unlink(f.name)


def spec_summary(run):
    spec = load(run / "spec" / "spec.json")
    cell = lambda x: str(x).replace("|", "\\|").replace("\n", " ")
    out = [f"**Objective:** {spec['objective']}", f"**Risk:** {spec['risk']} · **Tests:** {spec['tests_mode']}", "", "**Steps**"]
    out += [f"- {s['id']} {s['title']} (`{'`, `'.join(s['write_files'])}`; covers {', '.join(s['covers']) or 'none'})" for s in spec["steps"]]
    out += ["", "**Test cases**", "", "| id | title | given | when | expect |", "|---|---|---|---|---|"]
    out += [f"| {c['id']} | {cell(c['title'])} | {cell(c['given'])} | {cell(c['when'])} | {cell(c['expect'])} |" for c in spec["test_cases"]]
    return "\n".join(out)


def cost_lines(run):
    by, total = cost_by_role(run)
    return "\n".join([f"- {k}: ${v:.2f}" for k, v in sorted(by.items())] + [f"- total: ${total:.2f}"])


def dead_end(run, stage, reason):
    t = load(run / "ticket.json")
    tried = [cost_lines(run)]
    digests = sorted(run.glob("steps/*/attempt-*/failure.json"))
    if digests:
        d = load(digests[-1])
        tried.append(f"Last failure: `{d.get('stage')}` / `{d.get('class')}`: {d.get('message', '')[:300]}")
    body = (f"The ticket pipeline (run `{run.name}`) stopped at `{stage}`: {reason}\n\nWhat was tried (cost by role):\n" + "\n".join(tried)
            + f"\n\nThe worktree and branch `issue/{t['ticket_id']}` are left for you.")
    set_status(t["ticket_id"], "Ready")
    comment(t["ticket_id"], body)
    print(f"DEAD-END #{t['ticket_id']} {stage}")


def finish(run, wt):
    t, p = load(run / "ticket.json"), load(run / "project.json")
    n = t["ticket_id"]
    branch = p["git"]["branch"].format(n=n)
    if branch in ("main", "master"):
        die("ERROR finish refuses to push " + branch)
    fmt = os.environ.get("TICKET_FORMAT") or p["commands"]["format_fix"]
    subprocess.run(["bash", "-c", fmt], cwd=wt, check=False, capture_output=True)
    git("add", "-A", cwd=wt)
    if git("diff", "--cached", "--quiet", cwd=wt, check=False).returncode:
        git("commit", "-q", "-m", f"style: apply spotless for #{n}", cwd=wt)
    rf = run / "final-result.txt"
    result = rf.read_text().strip() if rf.is_file() else ""
    title = t["title"] if CONVENTIONAL.match(t["title"]) else f"{TYPES[t['kind']]}: {t['title']}"
    body = (f"Fixes #{n}\n\n## Final gate\n\n```\n{result}\n```\n\n## Spec\n\n{spec_summary(run)}\n\n## Cost by role\n\n{cost_lines(run)}\n")
    for need in p["git"]["pr_body_must_contain"]:
        if need.format(n=n) not in body or not result and need == "RESULT":
            die(f"ERROR finish: the PR body lacks '{need.format(n=n)}' (the final gate left no RESULT line?)")
    git("push", "-q", "-u", "origin", branch, cwd=wt)
    bf = run / "pr-body.md"
    bf.write_text(body)
    url = gh("pr", "create", "--draft", "-R", REPO, "--base", "main", "--head", branch, "--title", title, "--body-file", str(bf)).strip()
    (run / "pr.txt").write_text(url + "\n")
    print(f"PR {url}")


def main():
    a = argparse.ArgumentParser()
    a.add_argument("action"); a.add_argument("target"); a.add_argument("status", nargs="?")
    a.add_argument("--stage", default="?"); a.add_argument("--reason", default=""); a.add_argument("--worktree", default=".")
    o = a.parse_args()
    if o.action == "intake":
        return intake(o.target)
    if o.action == "claim":
        return set_status(o.target, o.status or "In progress") or 0
    run = pathlib.Path(o.target)
    if o.action == "plan-comment":
        comment(load(run / "ticket.json")["ticket_id"], f"Plan of the ticket pipeline (run `{run.name}`)\n\n{spec_summary(run)}\n")
        print("COMMENTED plan"); return 0
    if o.action == "dead-end":
        return dead_end(run, o.stage, o.reason) or 0
    if o.action == "finish":
        return finish(run, pathlib.Path(o.worktree).resolve()) or 0
    die(f"unknown action {o.action}")


sys.exit(main())
