#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit flow ACTION RUN [STEP] [--worktree W]: the glue of the `ticket` and `step` workflows (design 6, 8.2, 15.5), no model.

  preflight   RUN                    the walking skeleton runs one step of a low or medium risk spec; anything else stops here (approval, waves: later issues)
  step-begin  RUN STEP               commit the scaffold and locked tests as the base, record it, write the step packet (pack-step)
  validate-step RUN STEP --attempt N the ladder of 8.2: diff.patch from git, scope-check, regen, free-fix, then the step gate (yk check --no-heal --base B);
                                     a failure writes steps/S/attempt-N/failure.json (the digest) and exits 1
  commit-step RUN STEP               commit the validated step on the run branch and mark it DONE
  full-checks RUN                    the final gate (yk check --full)
  close       RUN [--events FILE]    print the cost by role and append the run's events to the global ticket-events.jsonl

TICKET_GATE_STEP / TICKET_GATE_FINAL (a shell command, {base} replaced) override the project's commands; the offline tests use them."""
import argparse, json, os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, die, git, load, save

STEPKIT = [sys.executable, str(KIT / "scripts" / "stepkit.py")]
TYPES = {"feature": "feat", "bug": "fix", "refactor": "refactor", "docs": "docs", "chore": "chore"}


def kit(*args, cwd=None):
    return subprocess.run([*STEPKIT, *args], capture_output=True, text=True, cwd=cwd)


def shell(cmd, cwd):
    return subprocess.run(["bash", "-c", cmd], capture_output=True, text=True, cwd=cwd)


def commit(wt, msg):
    git("add", "-A", cwd=wt)
    if git("diff", "--cached", "--quiet", cwd=wt, check=False).returncode:
        git("commit", "-q", "-m", msg, cwd=wt)


def fail_digest(run, step, attempt, stage, text, cmd, cls=None, rc=1):
    inp = run / "steps" / step / f"attempt-{attempt}" / "gate.out"
    inp.parent.mkdir(parents=True, exist_ok=True); inp.write_text(text)
    extra = ["--class", cls] if cls else []
    r = kit("digest", str(run), step, "--attempt", str(attempt), "--stage", stage, "--input", str(inp), "--exit-code", str(rc), "--command", cmd[:200], *extra)
    print(f"ERROR {step} {stage}: {(r.stdout or r.stderr).strip()[:400]}")


def gate(run, wt, step, attempt, template, override, stage_default):
    base = (run / "steps" / step / "base").read_text().strip() if step else "origin/main"
    cmd = os.environ.get(override, "-")
    cmd = template.format(base=base) if cmd in ("", "-") else cmd.format(base=base)
    r = shell(cmd, wt)
    text = r.stdout + r.stderr
    if r.returncode:
        m = re.search(r"failed_node=(\S+)", text)
        fail_digest(run, step or "S0", attempt, (m.group(1) if m and m.group(1) != "-" else stage_default), text, cmd, rc=r.returncode)
    return r.returncode


def main():
    a = argparse.ArgumentParser()
    a.add_argument("action"); a.add_argument("run"); a.add_argument("step", nargs="?")
    a.add_argument("--worktree", default="."); a.add_argument("--attempt", type=int, default=1); a.add_argument("--events")
    o = a.parse_args()
    run, wt = pathlib.Path(o.run), pathlib.Path(o.worktree).resolve()
    t, p = load(run / "ticket.json"), load(run / "project.json")
    sd = run / "steps" / (o.step or "S0")
    if o.action == "preflight":
        r, spec = load(run / "routing.json"), load(run / "spec" / "spec.json")
        if r["risk"] == "high":
            die("ERROR high risk: the spec needs approval (yk approve), which the walking skeleton does not have yet")
        if len(spec["steps"]) != 1:
            die(f"ERROR the walking skeleton runs one step, the spec has {len(spec['steps'])}")
        if spec["steps"][0]["id"] != "S1":
            die(f"ERROR the walking skeleton names its one step S1, not {spec['steps'][0]['id']}")
        print("S1")
    elif o.action == "step-begin":
        commit(wt, f"{TYPES[t['kind']]}: scaffold and locked tests for #{t['ticket_id']} (ticket pipeline)")
        sd.mkdir(parents=True, exist_ok=True)
        (sd / "base").write_text(git("rev-parse", "HEAD", cwd=wt).stdout.strip() + "\n")
        r = kit("pack-step", str(run), o.step, "--worktree", str(wt), "--attempt", str(o.attempt))
        print(r.stdout.strip() or r.stderr.strip())
        sys.exit(r.returncode)
    elif o.action == "validate-step":
        base = (sd / "base").read_text().strip()
        ad = sd / f"attempt-{o.attempt}"; ad.mkdir(parents=True, exist_ok=True)
        git("add", "-N", ".", cwd=wt)
        (ad / "diff.patch").write_text(git("diff", "--binary", base, cwd=wt).stdout)  # the authoritative diff: git, not the agent's claim
        for sub in ("regen", "scope-check"):
            r = kit(sub, str(run), o.step, "--base", base, "--worktree", str(wt))
            if r.returncode:
                sc = load(sd / "scope.json") if (sd / "scope.json").is_file() else {}
                errs = sc.get("errors") or [{}]
                fail_digest(run, o.step, o.attempt, "scope", r.stdout + r.stderr, sub, cls=errs[0].get("class", "scope_violation"), rc=r.returncode)
                sys.exit(1)
        kit("free-fix", str(run), "--base", base, "--worktree", str(wt))
        rc = gate(run, wt, o.step, o.attempt, p["commands"]["gate_step"], "TICKET_GATE_STEP", "tests")
        save(ad / "validation.json", {"ok": rc == 0, "base": base})
        sys.exit(1 if rc else 0)
    elif o.action == "commit-step":
        spec = load(run / "spec" / "spec.json")
        title = next(s["title"] for s in spec["steps"] if s["id"] == o.step)
        commit(wt, f"{TYPES[t['kind']]}: {title[:60].lower()} (#{t['ticket_id']} {o.step})")
        save(sd / "status.json", {"status": "DONE"})
        print(f"DONE {o.step}")
    elif o.action == "full-checks":
        sys.exit(1 if gate(run, wt, None, 1, p["commands"]["gate_final"], "TICKET_GATE_FINAL", "full") else 0)
    elif o.action == "close":
        by, total = {}, 0.0
        lines = (run / "events.jsonl").read_text().splitlines() if (run / "events.jsonl").is_file() else []
        for l in lines:
            e = json.loads(l)
            if e.get("cost_usd"):
                by[e["role"]] = by.get(e["role"], 0) + e["cost_usd"]; total += e["cost_usd"]
        if o.events and lines:
            pathlib.Path(o.events).parent.mkdir(parents=True, exist_ok=True)
            with open(o.events, "a") as f:
                f.write("\n".join(lines) + "\n")
        print(f"TICKET {run.name} cost=${total:.2f} by_role=" + ",".join(f"{k}:${v:.2f}" for k, v in sorted(by.items())))
    else:
        die(f"unknown action {o.action}")


main()
