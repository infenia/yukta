#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit flow ACTION RUN [STEP] [--worktree W]: the glue of the `ticket` and `step` workflows (design 6, 8.2, 15.5), no model.

  preflight   RUN                    the walking skeleton runs one step of a low or medium risk spec; anything else stops here (approval, waves: later issues)
  commit-tests RUN                   commit the stubs and locked tests; the base of every step not done is reset to it
  step-begin  RUN STEP               commit the scaffold and locked tests as the base, record it, write the step packet (pack-step)
  validate-step RUN STEP --attempt N the ladder of 8.2: diff.patch from git, scope-check, regen, free-fix, then the step gate (yk check --no-heal --base B);
                                     a failure writes steps/S/attempt-N/failure.json (the digest) and exits 1
  commit-step RUN STEP               commit the validated step on the run branch and mark it DONE
  full-checks RUN                    the final gate (yk check --full)
  close       RUN [--events FILE]    print the cost by role and append the run's events to the global ticket-events.jsonl

TICKET_GATE_STEP / TICKET_GATE_FINAL (a shell command, {base} replaced) override the project's commands; the offline tests use them."""
import argparse, json, os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, cost_by_role, die, git, load, resolve_attempt, save

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


def attempt_event(run, sid, attempt, status, cls="-"):
    """One telemetry line per attempt outcome: what the tuning report groups by (model, effort, complexity)."""
    routing, spec = load(run / "routing.json"), load(run / "spec" / "spec.json")
    mf = run / "steps" / sid / "model.json"
    m = load(mf) if mf.is_file() else routing["models"]["implementer"]
    cx = next((x.get("complexity", "standard") for x in spec["steps"] if x["id"] == sid), "standard")
    kit("event", str(run), "role=validate", f"step_id={sid}", f"attempt={attempt}", f"status={status}", f"fail_class={cls}", f"model={m['model']}", f"effort={m['effort']}", f"complexity={cx}")


def mutation(run, wt):
    """pitest on the changed classes (design 15.6), only at medium or high risk, never for :boot, :plus:* or :test-support. Survivors above the
    threshold go to the reviewer as a finding (review/mutation.json). Never fails the run: a missing tool is a note."""
    import xml.etree.ElementTree as ET
    p, routing = load(run / "project.json"), load(run / "routing.json")
    out = run / "review" / "mutation.json"
    out.parent.mkdir(exist_ok=True)
    if routing["risk"] == "low":
        save(out, {"skipped": "low risk"}); print("SKIPPED mutation: low risk"); return
    bases = sorted((run / "steps").glob("*/base"))
    first = bases[0].read_text().strip() if bases else "origin/main"
    files = [f for f in git("diff", "--name-only", first, "HEAD", cwd=wt).stdout.split() if "/src/main/java/" in f and f.endswith(".java")]
    mods = {}
    for f in files:
        mod, _, rest = f.partition("/src/main/java/")
        if mod.split("/")[0] in ("boot", "plus", "test-support") or mod.startswith("test-support"):
            continue
        mods.setdefault(mod.replace("/", ":"), []).append(rest[:-5].replace("/", "."))
    if not mods:
        save(out, {"skipped": "no mutable Java classes changed"}); print("SKIPPED mutation: nothing to mutate"); return
    survived = total = 0
    for mod, classes in sorted(mods.items()):
        cmd = os.environ.get("TICKET_MUTATION_CMD", "-")
        cmd = p["commands"]["mutation"] if cmd in ("", "-") else cmd
        shell(cmd.format(module=mod, classes=",".join(classes)), wt)
        for rep_ in (wt / mod.replace(":", "/")).glob("build/reports/pitest/**/mutations.xml"):
            for m in ET.parse(rep_).getroot().iter("mutation"):
                total += 1; survived += m.get("status") == "SURVIVED"
    thr = p.get("mutation", {}).get("max_survivor_rate", 0.25)
    rate = survived / total if total else 0.0
    save(out, {"modules": sorted(mods), "classes": sum(len(c) for c in mods.values()), "survivors": survived, "total": total, "rate": round(rate, 2), "threshold": thr, "exceeds": total > 0 and rate > thr})
    print(f"MUTATION survivors={survived}/{total} threshold={thr}")


def baseline_fails(wt, stage):
    """Does STAGE also fail on a clean origin/main? (TICKET_BASELINE_CMD, else yk check --stage, in a detached worktree)"""
    cmd = os.environ.get("TICKET_BASELINE_CMD", "-")
    if cmd == "-":
        cmd = "yk check --no-heal --stage {stage} --base origin/main"
    tmp = pathlib.Path(subprocess.run(["mktemp", "-d"], capture_output=True, text=True).stdout.strip())
    try:
        git("worktree", "add", "-q", "--detach", str(tmp / "w"), "origin/main", cwd=wt)
        return shell(cmd.format(stage=stage.replace(".", "_")), tmp / "w").returncode != 0
    finally:
        git("worktree", "remove", "--force", str(tmp / "w"), cwd=wt, check=False)
        subprocess.run(["rm", "-rf", str(tmp)])


def gate(run, wt, step, attempt, template, override, stage_default):
    base = (run / "steps" / step / "base").read_text().strip() if step else "origin/main"
    cmd = os.environ.get(override, "-")
    cmd = template.format(base=base) if cmd in ("", "-") else cmd.format(base=base)
    r = shell(cmd, wt)
    text = r.stdout + r.stderr
    if step is None:
        res = [l for l in text.splitlines() if l.startswith("RESULT ")]
        (run / "final-result.txt").write_text((res[-1] if res else "") + "\n")
    if r.returncode:
        m = re.search(r"failed_node=(\S+)", text)
        stage = m.group(1) if m and m.group(1) != "-" else stage_default
        fail_digest(run, step or "S0", attempt, stage, text, cmd, rc=r.returncode)
        if step and baseline_fails(wt, stage):  # the same stage fails on a clean origin/main: not the implementer's fault
            save(run / "steps" / step / f"attempt-{attempt}" / "baseline.json", {"stage": stage})
    return r.returncode


def main():
    a = argparse.ArgumentParser()
    a.add_argument("action"); a.add_argument("run"); a.add_argument("step", nargs="?")
    a.add_argument("--worktree", default="."); a.add_argument("--attempt", default="cur"); a.add_argument("--events")
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
    elif o.action == "commit-tests":  # the stubs and locked tests become the base of every step not yet done (also after a revision)
        commit(wt, f"{TYPES[t['kind']]}: scaffold and locked tests for #{t['ticket_id']} (ticket pipeline)")
        for s_ in load(run / "spec" / "spec.json")["steps"]:
            if not (run / "steps" / s_["id"] / "status.json").is_file():
                (run / "steps" / s_["id"] / "base").unlink(missing_ok=True)
        print("COMMITTED tests")
    elif o.action == "step-begin":
        sd.mkdir(parents=True, exist_ok=True)
        if not (sd / "base").is_file():  # a retry builds on the same base; the failed attempt's edits are reset first
            commit(wt, f"{TYPES[t['kind']]}: scaffold and locked tests for #{t['ticket_id']} (ticket pipeline)")
            (sd / "base").write_text(git("rev-parse", "HEAD", cwd=wt).stdout.strip() + "\n")
        else:
            git("reset", "-q", "--hard", (sd / "base").read_text().strip(), cwd=wt); git("clean", "-q", "-fd", "-e", "node_modules", cwd=wt)
        att = resolve_attempt(sd, "next" if o.attempt == "cur" else o.attempt)
        r = kit("pack-step", str(run), o.step, "--worktree", str(wt), "--attempt", str(att))
        print(r.stdout.strip() or r.stderr.strip())
        sys.exit(r.returncode)
    elif o.action == "validate-step":
        base = (sd / "base").read_text().strip()
        o.attempt = resolve_attempt(sd, o.attempt)
        ad = sd / f"attempt-{o.attempt}"
        git("add", "-N", ".", cwd=wt)
        (ad / "diff.patch").write_text(git("diff", "--binary", base, cwd=wt).stdout)  # the authoritative diff: git, not the agent's claim
        for sub in ("regen", "scope-check"):
            r = kit(sub, str(run), o.step, "--base", base, "--worktree", str(wt))
            if r.returncode:
                sc = load(sd / "scope.json") if (sd / "scope.json").is_file() else {}
                errs = sc.get("errors") or [{}]
                fail_digest(run, o.step, o.attempt, "scope", r.stdout + r.stderr, sub, cls=errs[0].get("class", "scope_violation"), rc=r.returncode)
                attempt_event(run, o.step, o.attempt, "fail", errs[0].get("class", "scope_violation"))
                sys.exit(1)
        kit("free-fix", str(run), "--base", base, "--worktree", str(wt))
        rc = gate(run, wt, o.step, o.attempt, p["commands"]["gate_step"], "TICKET_GATE_STEP", "tests")
        save(ad / "validation.json", {"ok": rc == 0, "base": base})
        attempt_event(run, o.step, o.attempt, "pass" if rc == 0 else "fail", "-" if rc == 0 else load(ad / "failure.json").get("class", "-"))
        sys.exit(1 if rc else 0)
    elif o.action == "commit-step":
        spec = load(run / "spec" / "spec.json")
        title = next(s["title"] for s in spec["steps"] if s["id"] == o.step)
        commit(wt, f"{TYPES[t['kind']]}: {title[:60].lower()} (#{t['ticket_id']} {o.step})")
        n = resolve_attempt(sd, "cur")
        save(sd / "status.json", {"status": "DONE", "baseline_failure": (sd / f"attempt-{n}" / "baseline.json").is_file()})
        print(f"DONE {o.step}")
    elif o.action == "full-checks":
        sys.exit(1 if gate(run, wt, None, 1, p["commands"]["gate_final"], "TICKET_GATE_FINAL", "full") else 0)
    elif o.action == "mutation":
        mutation(run, wt)
    elif o.action == "close":
        by, total = cost_by_role(run)
        lines = (run / "events.jsonl").read_text().splitlines() if (run / "events.jsonl").is_file() else []
        if o.events and lines:
            pathlib.Path(o.events).parent.mkdir(parents=True, exist_ok=True)
            with open(o.events, "a") as f:
                f.write("\n".join(lines) + "\n")
        print(f"TICKET {run.name} cost=${total:.2f} by_role=" + ",".join(f"{k}:${v:.2f}" for k, v in sorted(by.items())))
    else:
        die(f"unknown action {o.action}")


main()
