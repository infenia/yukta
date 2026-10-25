#!/usr/bin/env python3 -I
"""Collect real failures of the repository's CI for the triage evaluation (README.md in this directory).

  collect.py [--repo infenia/yukta] [--limit 200] [--out data/cases.json] [--logs DIR]

For every failed run of the Gradle, lint, REUSE and license workflows it keeps what heal's `brief` step would have shown the
triage model (the stage, the problem lines, the changed files and the first lines of the log) and, as an objective hint of how
hard the fix was, what happened next on the same branch: how many failed runs it took to get green and how big the change to
the next green commit was. Needs the `gh` CLI, logged in. Logs are downloaded to --logs (default data/logs) and kept there.
The output holds log text of your CI: it stays out of git (data/ is ignored); labels.json holds the labels by run id.
"""
import argparse, json, os, re, subprocess, sys

WORKFLOWS = {"Java CI with Gradle", "License Compliance Check"}
STEPS = re.compile(r"Gradle build|Spotless|REUSE|licenses|actionlint|Check the title|Run the examples|Fast Lint", re.I)
ANSI = re.compile(r"(\x1b|\^)?\[[0-9;]*[A-Za-z]")
# what a CI log has that `yk check` output does not: the environment of the step and the build tool's chatter
NOISE = re.compile(r"^(##\[(warning|group|endgroup|debug)\]|\s*Did you mean|\s*[A-Za-z_-]+: (true|false|\S+)$|Not performing cache|CI Gate\t|\[Cache cleanup\]|\s*(public|private|protected) )")
# where the failure starts in the log of a step
START = re.compile(r"FAILED$|What went wrong|: error: |^error: |Rule violated|violations? (were )?found|had format violations|MISSING COPYRIGHT|Unlicensed|^\s*[×✗]|Process completed with exit code")
PREFIX = re.compile(r"^[^\t]*\t[^\t]*\t\d{4}-\d\d-\d\dT[\d:.]+Z ?")
PROBLEM = re.compile(r"(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation", re.I)


def gh(*args, check=True):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "api-cache")


def gh_json(*args):
    """The JSON a gh call prints, kept under data/api-cache so a second collection only asks for what is new."""
    key = os.path.join(CACHE, __import__("hashlib").sha1(" ".join(args).encode()).hexdigest() + ".json")
    if os.path.exists(key):
        return json.load(open(key))
    try:
        d = json.loads(gh(*args))
    except (RuntimeError, ValueError):
        return None
    os.makedirs(CACHE, exist_ok=True)
    json.dump(d, open(key, "w"))
    return d


def clean(log):
    return [ANSI.sub("", PREFIX.sub("", l)).rstrip() for l in log.splitlines()]


def stage_of(lines, job, step):
    text = "\n".join(lines)
    failed_tasks = re.findall(r"> Task (\S+) FAILED", text)
    # the failed tasks say what failed; the job and step only when there are none (a job named "Spotless, Checkstyle, PMD" is all lint)
    names = " ".join(failed_tasks) if failed_tasks else step + " " + job
    rules = [("spotless", r"spotless"), ("reuse", r"REUSE|reuse lint"), ("licenses", r"licensee|license"), ("actionlint", r"actionlint"),
             ("coverage", r"jacoco|Rule violated"), ("style", r"checkstyle|pmd|opengrep"), ("spotbugs", r"spotbugs"),
             ("tests", r":test\b|Test\b|tests completed|integrationTest"), ("compile", r"compile|error: ")]
    for stage, rx in rules:
        if re.search(rx, names, re.I):
            return stage
    for stage, rx in rules:
        if re.search(rx, text, re.I):
            return stage
    return "other"


def failure_part(lines):
    """The lines of the failing step from where the failure starts, without the environment and tool chatter."""
    i = next((k for k, l in enumerate(lines) if START.search(l)), 0)
    return [l for l in lines[max(0, i - 2):] if not NOISE.search(l)]


def problem_lines(lines, n=25):
    out, ctx = [], 0
    for l in lines:
        if "What went wrong" in l:
            ctx = 3; out.append(l); continue
        if ctx:
            ctx -= 1; out.append(l); continue
        if PROBLEM.search(l):
            out.append(l)
    seen, res = set(), []
    for l in out:
        l = l[:300]
        if l not in seen:
            seen.add(l); res.append(l)
    return res[:n]


def excerpt(lines, n=60):
    """The end of the failing step: a build tool reports what failed last."""
    return [l[:300] for l in lines[-n:]]


def files_between(repo, base, head):
    d = gh_json("api", f"repos/{repo}/compare/{base}...{head}")
    if not d:
        return None
    fs = d.get("files") or []
    return {"files": len(d.get("files") or []), "lines": sum(f.get("additions", 0) + f.get("deletions", 0) for f in fs),
            "list": [f"{f['filename']} (+{f.get('additions', 0)} -{f.get('deletions', 0)})" for f in fs[:20]], "total": d.get("total_commits")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="infenia/yukta")
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "data", "cases.json"))
    ap.add_argument("--logs", default=None)
    a = ap.parse_args()
    logs = a.logs or os.path.join(os.path.dirname(a.out), "logs")
    os.makedirs(logs, exist_ok=True)
    failed = gh_json("run", "list", "-R", a.repo, "--status", "failure", "--limit", str(a.limit),
                     "--json", "databaseId,workflowName,headBranch,headSha,createdAt,url") or []
    allruns = []
    for wf in sorted({r["workflowName"] for r in failed if r["workflowName"] in WORKFLOWS}):
        allruns += gh_json("run", "list", "-R", a.repo, "--workflow", wf, "--limit", "1000",
                           "--json", "databaseId,workflowName,headBranch,headSha,conclusion,createdAt") or []
    cases = []
    for r in failed:
        if r["workflowName"] not in WORKFLOWS:
            continue
        rid = r["databaseId"]
        jobs = gh_json("api", f"repos/{a.repo}/actions/runs/{rid}/jobs") or {"jobs": []}
        bad = [(j["name"], next((s["name"] for s in j["steps"] if s["conclusion"] == "failure"), "")) for j in jobs["jobs"] if j["conclusion"] == "failure"]
        bad = [(j, s) for j, s in bad if STEPS.search(j + " " + s) and "CI Gate" not in j]
        if not bad:
            continue
        path = os.path.join(logs, f"{rid}.txt")
        if not os.path.exists(path):
            open(path, "w").write(gh("run", "view", str(rid), "-R", a.repo, "--log-failed", check=False))
        lines = clean(open(path, errors="replace").read())
        if len(lines) < 3:
            continue
        job, step = bad[0]
        stage = stage_of(lines, job, step)
        lines = failure_part(lines)
        problems = problem_lines(lines)
        mine = files_between(a.repo, "main", r["headSha"])
        later = sorted((x for x in allruns if x["workflowName"] == r["workflowName"] and x["headBranch"] == r["headBranch"] and x["createdAt"] > r["createdAt"]),
                       key=lambda x: x["createdAt"])
        green = next((x for x in later if x["conclusion"] == "success"), None)
        between = [x for x in later[:later.index(green)] if x["conclusion"] == "failure"] if green else []
        fix = files_between(a.repo, r["headSha"], green["headSha"]) if green and green["headSha"] != r["headSha"] else None
        short = "\n".join([f"stage: {stage}", f"job: {job} / {step}", *problems, f"files changed so far: {mine['files'] if mine else 'unknown'}"])
        long = "\n".join(["changed files: " + (", ".join(mine["list"]) + (f", ... {mine['files']} files" if mine["files"] > 20 else "") if mine and mine["list"] else "unknown"),
                          "log:", *excerpt(lines)])
        cases.append({"id": str(rid), "stage": stage, "short": short[:4000], "long": long[:8000],
                      "source": {"run": r["url"], "branch": r["headBranch"], "sha": r["headSha"], "job": job, "step": step},
                      "next": {"green_after_failed_runs": len(between) + 1 if green else None, "fix_files": fix["files"] if fix else None,
                               "fix_lines": fix["lines"] if fix else None, "fix_commits": fix["total"] if fix else None,
                               "fix_list": fix["list"] if fix else None}})
        print(f"{rid} {stage:10} {job} / {step}", file=sys.stderr)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(cases, open(a.out, "w"), indent=1)
    print(f"{len(cases)} cases -> {a.out}")


if __name__ == "__main__":
    main()
