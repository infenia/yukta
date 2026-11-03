#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit ladder ACTION RUN ...: failure routing, budgets, revision and approvals of the pipeline (design 6.3, 9, 15.5). No model.

  decide STEP [--after-heal] --worktree W     what to do after a failed attempt (the highest steps/STEP/attempt-N): prints JSON {action, model, effort, reason}
                                 action: retry | tier_up | heal | baseline | revise | human | pause. A terminal action (revise, human, pause) is also
                                 written to escalation.json. Rules: BLOCKED with a spec category -> revise; licenses/security -> human; infra_error and
                                 timeout -> rerun once, then human; a stage that also fails on clean origin/main -> baseline; style, spotbugs, ui_*,
                                 cli_* -> heal (once), then revise; scope-type classes -> retry once; compile, tests, coverage -> attempt 2 same model,
                                 attempt 3 one tier up, then revise (3 attempts, +1 after a revision); a budget over its limit -> pause
  heal STEP --worktree W         yk check --stage <failed stage> (its heal ladder), then revert everything outside write_files and any locked test
                                 (recorded in steps/STEP/scope-violations.jsonl)
  next-step                      prints {"step": "S2", "more": true} for the first step not DONE whose depends_on are DONE, else {"step": null, "more": false}
  route-failure                  prints {"action": ..., "trigger": ...} from escalation.json
  build-revision                 spec/revision-request.json (RevisionRequest) from escalation.json
  apply-revision                 spec/revision-result.json -> spec v+1 (completed steps are immutable); writes spec/changed_cases.json
  final-fail                     the final gate failed: escalation.json asks for a revision
  review-verdict                 a blocker finding overrides APPROVE; TESTS_FAITHFUL not met is a major finding; both -> CHANGES_REQUESTED
  need-approval KIND             {"ask": bool} for KIND spec (high risk) or merge (project.json merge_gate: high_risk | always | never)
  pause-packet                   human-packet.md: ticket, spec, last failure digests, diff stat
  ack-pause                      a human approved the pause: budgets double for the rest of the run
"""
import argparse, datetime, json, os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, cost_by_role, die, git, load, matches, save

SPEC_BLOCKS = {"spec_conflict", "contract_unclear", "missing_context", "needs_out_of_scope_change"}
MECH_CLASSES = {"scope_violation", "locked_file_modified", "patch_apply_fail", "format_fail"}
NEVER_MODEL = {"licenses", "security"}
TIERS = [("haiku", "medium"), ("sonnet", "medium"), ("sonnet", "high")]


def mechanical(stage):
    s = stage.replace(".", "_")
    return s in ("style", "spotbugs") or (s.startswith(("ui_", "cli_")) and s not in ("ui_vitest", "cli_cover_check"))


def ctx(run):
    return load(run / "project.json"), load(run / "routing.json")


def limits(p):
    return {"attempts_per_step": 3, "revisions_per_run": 2, "spec_retries": 2, **p.get("limits", {})}


def attempts_dirs(sd):
    return sorted(int(m.group(1)) for d in sd.glob("attempt-*") if (m := re.fullmatch(r"attempt-(\d+)", d.name)))


def revisions(run):
    f = run / "revisions.json"
    return load(f)["count"] if f.is_file() else 0


def factor(run):
    f = run / "extension.json"
    return load(f)["factor"] if f.is_file() else 1


def budget_breach(run, p, routing):
    cost = cost_by_role(run)[1]
    if cost > routing["budget_usd"] * factor(run):
        return f"cost ${cost:.2f} is over the ${routing['budget_usd'] * factor(run):.2f} budget"
    ev = run / "events.jsonl"
    if ev.is_file() and ev.stat().st_size:
        first = json.loads(ev.read_text().splitlines()[0])["ts"]
        mins = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.strptime(first, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)).total_seconds() / 60
        cap = p.get("budgets", {}).get("wall_clock_minutes", {"S": 60, "M": 120, "L": 240})[routing["complexity"]] * factor(run)
        if mins > cap:
            return f"wall clock {mins:.0f} min is over the {cap:.0f} min budget"
    return None


def tier_up(models):
    cur = (models["model"], models["effort"])
    i = TIERS.index(cur) if cur in TIERS else len(TIERS) - 1
    return TIERS[i + 1] if i + 1 < len(TIERS) else None


def ask_decision(*args):
    """One decision of the local model through `ticketkit decision` (stderr, with its WARN line, passes through). The last JSON line, or None."""
    r = subprocess.run([str(KIT / "ticketkit"), "decision", *args], stdout=subprocess.PIPE, text=True)
    lines = [l for l in r.stdout.splitlines() if l.startswith("{")]
    return json.loads(lines[-1]) if r.returncode == 0 and lines else None


def decide(run, sid, after_heal, wt):
    p, routing = ctx(run)
    sd = run / "steps" / sid
    n = attempts_dirs(sd)[-1]
    ad = sd / f"attempt-{n}"
    state = {"healed": [], "infra": 0, "mech": 0, "flaky": 0} | (load(sd / "ladder.json") if (sd / "ladder.json").is_file() else {})
    mf = sd / "model.json"
    cur = load(mf) if mf.is_file() else routing["models"]["implementer"]
    res = load(ad / "result.json") if (ad / "result.json").is_file() else {}
    fail = load(ad / "failure.json") if (ad / "failure.json").is_file() else {}
    cls = "blocked_reported" if res.get("status") == "BLOCKED" else fail.get("class", "infra_error")
    stage = fail.get("stage", "")
    allowed = limits(p)["attempts_per_step"] + (1 if revisions(run) else 0)

    def out(action, reason, model=None, effort=None, trigger="step_failed"):
        d = {"action": action, "reason": reason, "step": sid, "attempt": n, "model": model or cur["model"], "effort": effort or cur["effort"], "trigger": trigger}
        if action in ("revise", "human", "pause"):
            # the failed attempt's edits must not leak into the next base (a revision commits the new tests on top of the worktree)
            git("reset", "-q", "--hard", (sd / "base").read_text().strip(), cwd=wt); git("clean", "-q", "-fd", "-e", "node_modules", cwd=wt)
            if action == "revise" and revisions(run) >= limits(p)["revisions_per_run"]:
                d.update(action="human", reason=f"{reason}; the {limits(p)['revisions_per_run']} revisions of the run are used")
            save(run / "escalation.json", d)
        save(sd / "ladder.json", state)
        print(json.dumps(d))
        return 0

    breach = budget_breach(run, p, routing)
    if breach:
        return out("pause", breach)
    if cls == "blocked_reported":
        cat = (res.get("blocked") or {}).get("category")
        return out("revise", f"BLOCKED ({cat})", trigger="step_blocked") if cat in SPEC_BLOCKS else out("retry", f"BLOCKED ({cat}) is not a spec problem")
    if stage in NEVER_MODEL:
        return out("human", f"{stage} is never healed")
    if cls in ("infra_error", "timeout"):
        state["infra"] += 1
        return out("retry", f"{cls}: rerun unchanged") if state["infra"] == 1 else out("human", f"{cls} twice")
    if (ad / "baseline.json").is_file():
        return out("baseline", f"{stage} also fails on clean origin/main: baseline_failure, not charged to the implementer")
    if mechanical(stage):
        if after_heal or stage in state["healed"]:
            return out("revise", f"heal could not fix {stage}")
        state["healed"].append(stage)
        return out("heal", f"{stage} is mechanical: heal first")
    if after_heal:
        return out("revise", "heal left the step failing")
    if cls in MECH_CLASSES and state["mech"] == 0:
        state["mech"] += 1
        return out("retry", f"{cls}: retry once with the digest")
    if n >= 2 and cls in ("test_fail", "typecheck_fail") and stage in ("tests", "compile"):
        d = ask_decision("cause", str(run), sid)  # D2: only for tests and compile failures from attempt 2
        cause = d["label"] if d else "impl_bug"
        if cause in ("spec_gap", "spec_conflict"):
            return out("revise", f"cause: {cause}")
        if cause == "flaky" and state["flaky"] == 0:
            state["flaky"] += 1
            subprocess.run([sys.executable, str(KIT / "scripts" / "stepkit.py"), "event", str(run), "role=cause", f"step_id={sid}", "flaky=1"], check=True)
            return out("retry", "cause: flaky, rerun once unchanged")
    if n >= allowed:
        return out("revise", f"{n} attempts failed")
    if n == 2:
        up = tier_up(cur)
        if up:
            save(mf, {"model": up[0], "effort": up[1]})
            return out("tier_up", "attempt 3 goes one tier up", up[0], up[1])
    return out("retry", f"attempt {n + 1} on the same model with the digest")


def changed_files(wt, base):
    names = set(git("diff", "--name-only", base, cwd=wt).stdout.split())
    names |= set(git("ls-files", "--others", "--exclude-standard", cwd=wt).stdout.split())
    return {f for f in names if not re.search(r"(^|/)(build|node_modules|\.gradle|\.yukta-dev)/", f)}


def heal(run, sid, wt):
    p, _ = ctx(run)
    sd = run / "steps" / sid
    spec = load(run / "spec" / "spec.json")
    step = next(s for s in spec["steps"] if s["id"] == sid)
    base = (sd / "base").read_text().strip()
    stage = load(sd / f"attempt-{attempts_dirs(sd)[-1]}" / "failure.json")["stage"].replace(".", "_")
    cmd = os.environ.get("TICKET_HEAL_CMD", "-")
    cmd = p["commands"]["heal_stage"] if cmd in ("", "-") else cmd
    r = subprocess.run(["bash", "-c", cmd.format(stage=stage, base=base)], capture_output=True, text=True, cwd=wt)
    locked = load(run / "spec" / "locked.json") if (run / "spec" / "locked.json").is_file() else {}
    gen = [g["glob"] for g in p["generated"]]
    reverted = []
    for f in sorted(changed_files(wt, base)):
        if f in step["write_files"] and f not in locked or matches(f, gen):
            continue
        tracked = git("cat-file", "-e", f"{base}:{f}", cwd=wt, check=False).returncode == 0
        if tracked:
            git("checkout", base, "--", f, cwd=wt)
        else:
            (wt / f).unlink(missing_ok=True)
        reverted.append({"file": f, "why": "locked test" if f in locked else "outside write_files"})
    if reverted:
        with open(sd / "scope-violations.jsonl", "a") as fh:
            fh.write(json.dumps({"stage": stage, "reverted": reverted}) + "\n")
        subprocess.run([sys.executable, str(KIT / "scripts" / "stepkit.py"), "event", str(run), "role=heal", f"step_id={sid}", "scope_violation=1", f"files={len(reverted)}"], check=True)
    print(json.dumps({"healed_exit": r.returncode, "reverted": reverted}))
    return 0


def next_step(run):
    spec = load(run / "spec" / "spec.json")
    done = {s["id"] for s in spec["steps"] if (run / "steps" / s["id"] / "status.json").is_file()}
    for s in spec["steps"]:
        if s["id"] not in done and set(s["depends_on"]) <= done:
            print(json.dumps({"step": s["id"], "more": True})); return 0
    print(json.dumps({"step": None, "more": False})); return 0


def build_revision(run):
    esc = load(run / "escalation.json")
    sid = esc["step"]
    spec = load(run / "spec" / "spec.json")
    attempts = []
    for ad in sorted((run / "steps" / sid).glob("attempt-*")):
        ev = [json.loads(l) for l in (run / "events.jsonl").read_text().splitlines() if f'"step_id": "{sid}"' in l and f'"attempt": {ad.name.split("-")[1]},' in l and '"role": "implementer"' in l]
        m = ev[-1] if ev else {"model": "haiku", "effort": "medium"}
        attempts.append({"model": m["model"], "effort": m["effort"], "failure_digest_ref": f"steps/{sid}/{ad.name}/failure.json"})
    done = [s["id"] for s in spec["steps"] if (run / "steps" / s["id"] / "status.json").is_file()]
    req = {"run_id": run.name, "spec_version": spec["spec_version"], "trigger": esc["trigger"], "step_id": sid, "attempts": attempts, "completed_steps": done,
           "question": f"Why did {sid} fail and how should the spec change?"[:300]}
    save(run / "spec" / "revision-request.json", req)
    if subprocess.run([sys.executable, str(KIT / "scripts" / "validate-schema.py"), "revision-request", str(run / "spec" / "revision-request.json")], capture_output=True).returncode:
        die("ERROR the revision request is not valid")
    print(json.dumps({"trigger": req["trigger"], "step_id": sid})); return 0


def apply_revision(run):
    spec, rev = load(run / "spec" / "spec.json"), load(run / "spec" / "revision-result.json")
    done = {s["id"] for s in spec["steps"] if (run / "steps" / s["id"] / "status.json").is_file()}
    bad = [s["id"] for s in rev["replace_steps"] if s["id"] in done]
    if bad:
        die(f"ERROR the revision replaces completed steps {bad}; completed steps are immutable")
    by = {s["id"]: s for s in spec["steps"]}
    by.update({s["id"]: s for s in rev["replace_steps"]})
    cases = {c["id"]: c for c in spec["test_cases"]}
    cases.update({c["id"]: c for c in rev["replace_test_cases"]})
    for cid in rev["remove_test_case_ids"]:
        cases.pop(cid, None)
    key = lambda s: int(s["id"][1:])
    new = dict(spec, spec_version=spec["spec_version"] + 1, steps=sorted(by.values(), key=key), test_cases=sorted(cases.values(), key=lambda c: int(c["id"][1:])),
               assumptions=spec["assumptions"] + rev["new_assumptions"])
    save(run / "spec" / "spec.json", new)
    save(run / "spec" / f"spec.v{new['spec_version']}.json", new)
    save(run / "spec" / "changed_cases.json", sorted({c["id"] for c in rev["replace_test_cases"]} | set(rev["remove_test_case_ids"])))
    (run / "prefix.hash").unlink(missing_ok=True)  # the cache prefix legitimately changes with the spec
    (run / "escalation.json").unlink(missing_ok=True)
    save(run / "revisions.json", {"count": revisions(run) + 1})
    print(f"REVISED spec_version={new['spec_version']} replaced={sorted(s['id'] for s in rev['replace_steps'])}"); return 0


def review_verdict(run):
    r = load(run / "review" / "result.json")
    notes = []
    if any(f["severity"] == "blocker" for f in r["findings"]):
        notes.append("a blocker finding overrides APPROVE")
    for c in r["criteria_results"]:
        if c["id"] == "TESTS_FAITHFUL" and not c["met"]:
            r["findings"].append({"severity": "major", "path": "-", "issue": "the tests do not faithfully check the cases", "suggested_action": "revise the tests"})
            notes.append("TESTS_FAITHFUL not met is a major finding")
    if notes and r["verdict"] == "APPROVE":
        r["verdict"] = "CHANGES_REQUESTED"
    save(run / "review" / "result.json", r)
    if r["verdict"] != "APPROVE":
        save(run / "escalation.json", {"action": "revise", "trigger": "review_findings", "step": load(run / "spec" / "spec.json")["steps"][-1]["id"], "reason": "; ".join(notes) or "the reviewer requested changes"})
    print(json.dumps({"verdict": r["verdict"], "notes": notes})); return 0


def final_fail(run):
    spec = load(run / "spec" / "spec.json")
    save(run / "escalation.json", {"action": "revise", "trigger": "step_failed", "step": spec["steps"][-1]["id"], "reason": "the final gate failed"})
    print("ESCALATED final gate"); return 0


def need_approval(run, kind, wt):
    p, routing = ctx(run)
    risk = routing["risk"]
    policy = p.get("merge_gate", "high_risk")
    ask = risk == "high" if kind == "spec" else policy == "always" or (policy == "high_risk" and risk == "high")
    if kind == "merge":
        d = ask_decision("merge_risk", str(run), "-", str(wt))  # shadow: logged only; in mode on a high score also asks
        if d and d["source"] == "model" and int(d["label"]) >= p.get("merge_risk_ask", 7):
            ask = True
    print(json.dumps({"ask": ask, "kind": kind, "risk": risk})); return 0


def pause_packet(run):
    t = load(run / "ticket.json")
    esc = load(run / "escalation.json") if (run / "escalation.json").is_file() else {}
    lines = [f"# Run {run.name} is paused", f"Ticket #{t['ticket_id']}: {t['title']}", f"Why: {esc.get('reason', '?')} (step {esc.get('step', '-')})", "", "Last failure digests:"]
    for f in sorted(run.glob("steps/*/attempt-*/failure.json"))[-3:]:
        d = load(f)
        lines.append(f"- {f.parent.parent.name}/{f.parent.name}: {d.get('stage')} / {d.get('class')}: {d.get('message', '')[:200]}")
    by, total = cost_by_role(run)
    lines += ["", f"Cost so far: ${total:.2f} " + json.dumps(by), "Approve to continue with doubled budgets; reject to stop. Spec: spec/spec.json"]
    (run / "human-packet.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines)[:1500]); return 0


def ack_pause(run):
    save(run / "extension.json", {"factor": factor(run) * 2})
    (run / "escalation.json").unlink(missing_ok=True)
    print("budgets doubled"); return 0


def main():
    a = argparse.ArgumentParser()
    a.add_argument("action"); a.add_argument("run"); a.add_argument("arg", nargs="?")
    a.add_argument("--after-heal", action="store_true"); a.add_argument("--worktree", default=".")
    o = a.parse_args()
    run = pathlib.Path(o.run)
    f = {"decide": lambda: decide(run, o.arg, o.after_heal, pathlib.Path(o.worktree).resolve()), "heal": lambda: heal(run, o.arg, pathlib.Path(o.worktree).resolve()), "next-step": lambda: next_step(run),
         "route-failure": lambda: print(json.dumps(load(run / "escalation.json") if (run / "escalation.json").is_file() else {"action": "human", "trigger": "step_failed", "reason": "no escalation recorded"})) or 0,
         "build-revision": lambda: build_revision(run), "apply-revision": lambda: apply_revision(run), "review-verdict": lambda: review_verdict(run),
         "final-fail": lambda: final_fail(run), "need-approval": lambda: need_approval(run, o.arg, pathlib.Path(o.worktree).resolve()), "pause-packet": lambda: pause_packet(run), "ack-pause": lambda: ack_pause(run)}.get(o.action)
    return f() if f else die(f"unknown action {o.action}")


sys.exit(main())
