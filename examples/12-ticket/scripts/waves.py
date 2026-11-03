#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit waves ACTION RUN ...: parallel step waves (design 11.2, 15.10). Pairs: Gradle allows two runs at once, so max_parallel is 2.

  next-wave                         {"mode": none|single|pair, "step": first ready step, "steps": [ids]}. Ready = depends_on DONE. A pair needs
                                    max_parallel >= 2, disjoint write_files and no step that changes ui/package.json, the lockfile or a shared file
                                    (those run alone: installing in a linked worktree writes into the shared copy)
  prepare --worktree W              the second step gets its own worktree and branch from the same base commit; writes wave.json;
                                    prints {"items": [{step, worktree}, ...]}
  warm-wait STEP                    the sibling waits until the first step's first implementer call is in events.jsonl (its cache prefix is written),
                                    at most TICKET_WARM_TIMEOUT seconds (300): the prefix is paid once
  merge --worktree W                both steps DONE -> the second branch is merged into W in step order; a conflict is aborted and is an escalation
                                    attributed to the later step, never resolved silently
  check --worktree W                the post-wave check on the merged result (TICKET_GATE_WAVE, else the step gate against the wave base); a failure is
                                    an escalation attributed to the later step"""
import argparse, json, os, pathlib, subprocess, sys, time
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, die, git, load, save

SERIAL = {"ui/package.json", "ui/pnpm-lock.yaml", "cli/go.mod", "cli/go.sum"}


def done(run, sid):
    return (run / "steps" / sid / "status.json").is_file()


def event(run, *pairs):
    subprocess.run([sys.executable, str(KIT / "scripts" / "stepkit.py"), "event", str(run), *pairs], check=True)


def pick(run):
    p, spec = load(run / "project.json"), load(run / "spec" / "spec.json")
    ready = [s for s in spec["steps"] if not done(run, s["id"]) and all(done(run, d) for d in s["depends_on"])]
    if not ready:
        return {"mode": "none", "step": None, "steps": []}
    serial = SERIAL | set(p.get("shared_files") or [])
    alone = lambda s: bool(serial & set(s["write_files"]))
    first = ready[0]
    if p.get("max_parallel", 2) >= 2 and not alone(first):
        for other in ready[1:]:
            if not alone(other) and not set(first["write_files"]) & set(other["write_files"]):
                return {"mode": "pair", "step": first["id"], "steps": [first["id"], other["id"]]}
    return {"mode": "single", "step": first["id"], "steps": [first["id"]]}


def escalate(run, sid, why):
    save(run / "escalation.json", {"action": "revise", "trigger": "step_failed", "step": sid, "reason": why, "model": "-", "effort": "-"})
    event(run, "role=wave", f"step_id={sid}", "escalated=1")
    print(f"ERROR {why}")


def prepare(run, wt):
    w = pick(run)
    if w["mode"] != "pair":
        die("ERROR no pair is ready")
    a, b = w["steps"]
    t = load(run / "ticket.json")
    base = git("rev-parse", "HEAD", cwd=wt).stdout.strip()
    wt2 = wt.parent / f"{wt.name}-{b}"
    branch = f"issue/{t['ticket_id']}-{b}"
    git("worktree", "add", "-q", "-b", branch, str(wt2), base, cwd=wt)
    nm = wt / "ui" / "node_modules"
    if nm.is_dir() and not (wt2 / "ui" / "node_modules").exists():
        (wt2 / "ui" / "node_modules").symlink_to(nm)
    save(run / "wave.json", {"base": base, "steps": [a, b], "worktrees": {a: str(wt), b: str(wt2)}, "branch": branch})
    print(json.dumps({"items": [{"step": a, "worktree": str(wt)}, {"step": b, "worktree": str(wt2)}]}))
    return 0


def warm_wait(run, sid):
    f = run / "wave.json"
    if not f.is_file() or load(f)["steps"][1] != sid:
        return 0
    first = load(f)["steps"][0]
    end = time.time() + float(os.environ.get("TICKET_WARM_TIMEOUT", "300"))
    while time.time() < end:
        ev = run / "events.jsonl"
        if ev.is_file() and any(f'"step_id": "{first}"' in l and '"role": "implementer"' in l for l in ev.read_text().splitlines()):
            return 0
        time.sleep(0.5)
    print(f"WARN {first} never warmed the cache within the time box; going on")
    return 0


def merge(run, wt):
    w = load(run / "wave.json")
    a, b = w["steps"]
    for sid in w["steps"]:
        if not done(run, sid):
            die(f"ERROR {sid} did not finish; the wave is not merged")
    r = git("merge", "--no-ff", "-q", "-m", f"chore: merge {b} into the run branch", w["branch"], cwd=wt, check=False)
    if r.returncode:
        git("merge", "--abort", cwd=wt, check=False)
        escalate(run, b, f"merge conflict between {a} and {b}: " + (r.stdout + r.stderr).strip().splitlines()[-1][:200])
        return 1
    git("worktree", "remove", "--force", w["worktrees"][b], cwd=wt, check=False)
    git("branch", "-q", "-D", w["branch"], cwd=wt, check=False)
    print(f"MERGED {b} into the run branch")
    return 0


def check(run, wt):
    w = load(run / "wave.json")
    p = load(run / "project.json")
    cmd = os.environ.get("TICKET_GATE_WAVE", "-")
    cmd = p["commands"]["gate_step"] if cmd in ("", "-") else cmd
    r = subprocess.run(["bash", "-c", cmd.format(base=w["base"])], capture_output=True, text=True, cwd=wt)
    if r.returncode:
        escalate(run, w["steps"][1], f"the merged wave {w['steps']} fails its post-wave check: " + (r.stdout + r.stderr).strip()[-200:])
        return 1
    (run / "wave.json").rename(run / f"wave.{int(time.time() * 1000)}.done.json")
    print("WAVE ok")
    return 0


def main():
    a = argparse.ArgumentParser()
    a.add_argument("action"); a.add_argument("run"); a.add_argument("arg", nargs="?"); a.add_argument("--worktree", default=".")
    o = a.parse_args()
    run, wt = pathlib.Path(o.run), pathlib.Path(o.worktree).resolve()
    f = {"next-wave": lambda: print(json.dumps(pick(run))) or 0, "prepare": lambda: prepare(run, wt), "warm-wait": lambda: warm_wait(run, o.arg),
         "merge": lambda: merge(run, wt), "check": lambda: check(run, wt)}.get(o.action)
    return f() if f else die(f"unknown action {o.action}")


sys.exit(main())
