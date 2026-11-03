#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Per-step tools of ticketkit (design 5.2, 5.4, 8.2, 11.1, 15.5, 15.7). One subcommand per call:

  pack-step   RUN STEP [--worktree W] [--attempt N]   steps/<STEP>/packet.json + the byte-stable cache prefix (prefix.txt, prefix.hash)
  scope-check RUN STEP --base REF [--worktree W]      is the diff since REF acceptable for this step; ERROR lines, exit 1, steps/<STEP>/scope.json
  regen       RUN STEP --base REF [--worktree W]      run the regeneration commands of project.json for generated files whose trigger changed
  free-fix    RUN --base REF [--worktree W]           formatters for the changed files (never an attempt)
  digest      RUN STEP --attempt N --stage S [--class C] [--input FILE] [--junit DIR]   failure digest (<= 2 KB) to steps/<STEP>/attempt-N/failure.json
  event       RUN key=value... [--claude-result FILE]  append one line to events.jsonl (tokens and cache fields from a claude result)
"""
import argparse, datetime, hashlib, json, pathlib, re, subprocess, sys, xml.etree.ElementTree as ET
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import KIT, die, git, load, matches, save

SUPPRESS = re.compile(r"@SuppressWarnings|@SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|eslint-disable|//\s*nolint|#nosec|@ts-ignore|@ts-nocheck")
ASSERT = re.compile(r"\bassert\w*\(|\bexpect\(|\bassertThat\(|StepVerifier|\bt\.(Error|Fatal)f?\(")
TESTFILE = re.compile(r"(Test\.java|\.test\.tsx?|\.spec\.tsx?|_test\.go)$")
CLASSES = {"tests": "test_fail", "ui.vitest": "test_fail", "cli.cover-check": "test_fail", "coverage": "test_fail", "compile": "typecheck_fail", "ui.tsc": "typecheck_fail",
           "spotless": "format_fail", "ui.prettier": "format_fail", "cli.fmt-check": "format_fail", "style": "lint_fail", "spotbugs": "lint_fail", "ui.eslint": "lint_fail",
           "cli.vet": "lint_fail", "cli.lint": "lint_fail", "timeout": "timeout"}


def eco(path):
    return "ui" if path.startswith("ui/") else "go" if path.startswith("cli/") else "shell" if path.startswith(".github/") else "java"


def ctx(args):
    run = pathlib.Path(args.run)
    return run, pathlib.Path(getattr(args, "worktree", ".") or ".").resolve(), load(run / "project.json")


def step_of(run, sid):
    spec = load(run / "spec" / "spec.json")
    for s in spec["steps"]:
        if s["id"] == sid:
            return spec, s
    die(f"no step {sid} in the spec")


def sha(b):
    return hashlib.sha256(b).hexdigest()


def changed(wt, base):
    """{path: status} of the diff since BASE, untracked files included, build output left out."""
    out = {}
    for l in git("diff", "--name-status", "--no-renames", base, cwd=wt).stdout.splitlines():
        s, _, p = l.partition("\t")
        out[p] = s
    for p in git("ls-files", "--others", "--exclude-standard", cwd=wt).stdout.splitlines():
        out.setdefault(p, "A")
    return {p: s for p, s in out.items() if not re.search(r"(^|/)(build|node_modules|\.gradle|\.yukta-dev)/", p)}


def conventions(wt, project, ecos):
    lines = []
    for e in sorted(ecos):
        for src in project["conventions_sources"].get(e, []):
            p = wt / src
            files = [p] if p.is_file() else sorted(p.rglob("*.md")) if p.is_dir() else []
            for f in files:
                lines += [l.rstrip()[:160] for l in f.read_text(errors="replace").splitlines() if l.strip() and not l.startswith("---")]
    return "\n".join(lines[:60])


def pack(args):
    run, wt, project = ctx(args)
    spec, step = step_of(run, args.step)
    ticket = load(run / "ticket.json")
    for f in step["write_files"] + [r["path"] for r in step["read_files"]]:
        if matches(f, project["denied_read"]):
            die(f"ERROR {args.step}: {f} is on the deny list; an agent never reads it")
    ecos = {eco(f) for s in spec["steps"] for f in s["write_files"]}
    glob = {"forbidden_imports": spec["scope"]["forbidden_imports"], "allow_new_dependencies": spec["scope"]["allow_new_dependencies"],
            "locked_files": spec["locked_files"], "conventions_digest": conventions(wt, project, ecos)}
    # the cacheable prefix: identical for every step and retry of a run (sorted keys, no timestamps)
    prefix = json.dumps({"objective": spec["objective"], "scope": spec["scope"], "global": glob}, sort_keys=True, indent=1) + "\n"
    ph = "sha256:" + sha(prefix.encode())
    old = run / "prefix.hash"
    if old.is_file() and old.read_text().strip() != ph:
        die("ERROR the cache prefix changed within the run (spec scope, locked files or conventions changed); a revision must reset it with `rm prefix.hash`")
    (run / "prefix.txt").write_text(prefix); old.write_text(ph + "\n")
    ctxf = []
    for rf in step["read_files"]:
        p = wt / rf["path"]
        if p.is_file():
            body = p.read_text(errors="replace").splitlines()
            a, b = (map(int, rf["lines"].split("-")) if rf.get("lines") else (1, len(body)))
            ctxf.append({"path": rf["path"], "lines": f"{a}-{b}", "content": "\n".join(body[a - 1:b])})
    for f in step["write_files"]:
        p = wt / f
        if p.is_file() and f not in {c["path"] for c in ctxf}:
            ctxf.append({"path": f, "content": p.read_text(errors="replace")})
    sd = run / "steps" / args.step
    prior = []
    for fp in sorted(sd.glob("attempt-*/failure.json")):
        prior.append({"attempt": int(re.search(r"attempt-(\d+)", str(fp)).group(1)), "failure_digest": fp.read_text()[:2048]})
    packet = {"run_id": run.name, "spec_hash": "sha256:" + sha((run / "spec" / "spec.json").read_bytes()), "step": step,
              "cases": [c for c in spec["test_cases"] if c["id"] in step["covers"]], "global": glob, "context_files": ctxf, "prior_attempts": prior}
    sd.mkdir(parents=True, exist_ok=True)
    save(sd / "packet.json", packet)
    r = subprocess.run([sys.executable, str(KIT / "scripts" / "validate-schema.py"), "step-packet", str(sd / "packet.json")], capture_output=True, text=True)
    if r.returncode:
        die("ERROR packet is not valid: " + r.stdout)
    event(argparse.Namespace(run=str(run), pairs=[f"role=pack", f"step_id={args.step}", f"prefix_hash={ph}"], claude_result=None))
    print(f"PACKED {args.step} prefix_hash={ph} context_files={len(ctxf)} prior_attempts={len(prior)}")


def scope(args):
    run, wt, project = ctx(args)
    spec, step = step_of(run, args.step)
    errors = []
    err = lambda c, m: errors.append({"class": c, "message": m})
    ch = changed(wt, args.base)
    gen = [g["glob"] for g in project["generated"]]
    marker = load(run / "steps" / args.step / "regen.json") if (run / "steps" / args.step / "regen.json").is_file() else {}
    locked = load(run / "spec" / "locked.json") if (run / "spec" / "locked.json").is_file() else {}
    for f, hsh in locked.items():
        p = wt / f
        if not p.is_file() or sha(p.read_bytes()) != hsh:
            err("locked_file_modified", f"{f} was changed")
    writes = set(step["write_files"])
    counted_lines = 0
    for f, st in sorted(ch.items()):
        if f in locked:
            continue
        if matches(f, gen):
            if f in marker and (wt / f).is_file() and marker[f] == sha((wt / f).read_bytes()):
                continue
            err("scope_violation", f"{f} is generated and was edited by hand; it is regenerated by `ticketkit regen`")
            continue
        if f not in writes:
            err("scope_violation", f"{f} is not in write_files")
        if matches(f, project["denied_write"]):
            err("scope_violation", f"{f} may not be edited by an agent")
        if matches(f, project["dependency_files"]) and not spec["scope"]["allow_new_dependencies"]:
            err("scope_violation", f"{f} is a dependency file and allow_new_dependencies is false")
        if matches(f, spec["scope"]["forbidden_globs"]):
            err("scope_violation", f"{f} is in forbidden_globs")
    # content rules on the added / removed lines of the step's own files
    for f in sorted(set(ch) - set(locked)):
        if matches(f, gen):
            continue
        d = git("diff", "-U0", "--no-color", args.base, "--", f, cwd=wt, check=False).stdout
        if not d and (wt / f).is_file():  # untracked: everything is added
            d = "".join("+" + l + "\n" for l in (wt / f).read_text(errors="replace").splitlines())
        add = [l[1:] for l in d.splitlines() if l.startswith("+") and not l.startswith("+++")]
        rem = [l[1:] for l in d.splitlines() if l.startswith("-") and not l.startswith("---")]
        counted_lines += len(add) + len(rem)
        for l in add:
            if SUPPRESS.search(l):
                err("scope_violation", f"{f}: new suppression `{l.strip()[:80]}`")
            for imp in spec["scope"]["forbidden_imports"]:
                if re.search(rf"\b(import|from|require)\b.*['\"\s.]{re.escape(imp)}\b", l):
                    err("scope_violation", f"{f}: forbidden import {imp}")
        if TESTFILE.search(f) and sum(bool(ASSERT.search(l)) for l in rem) > sum(bool(ASSERT.search(l)) for l in add):
            err("scope_violation", f"{f}: assertions were removed")
    if len([f for f in ch if f not in locked and not matches(f, gen)]) > step["limits"]["max_files"]:
        err("scope_violation", f"{len(ch)} files changed, limits.max_files is {step['limits']['max_files']}")
    if counted_lines > step["limits"]["max_diff_lines"]:
        err("scope_violation", f"{counted_lines} diff lines (generated files not counted) exceed limits.max_diff_lines {step['limits']['max_diff_lines']}")
    sd = run / "steps" / args.step
    sd.mkdir(parents=True, exist_ok=True)
    save(sd / "scope.json", {"errors": errors, "diff_lines": counted_lines})
    for e in errors:
        print(f"ERROR {e['class']}: {e['message']}")
    print(f"RESULT scope-check errors={len(errors)} diff_lines={counted_lines}")
    return 1 if errors else 0


def regen(args):
    run, wt, project = ctx(args)
    sd = run / "steps" / args.step
    sd.mkdir(parents=True, exist_ok=True)
    marker, ran = {}, set()
    for _ in range(len(project["generated"]) + 1):  # a regenerated file may trigger the next one
        ch = changed(wt, args.base)
        todo = [g for g in project["generated"] if g["glob"] not in ran and any(matches(p, [g["trigger"]]) for p in ch)]
        if not todo:
            break
        for g in todo:
            ran.add(g["glob"])
            r = subprocess.run(g["regen"], shell=True, cwd=wt, capture_output=True, text=True)
            if r.returncode:
                print(f"ERROR regen `{g['regen']}` exited {r.returncode}: {(r.stdout + r.stderr).strip()[-300:]}")
                return 1
    for p in changed(wt, args.base):
        if matches(p, [g["glob"] for g in project["generated"]]) and (wt / p).is_file():
            marker[p] = sha((wt / p).read_bytes())
    save(sd / "regen.json", marker)
    print(f"REGEN ran={sorted(ran)} files={sorted(marker)}")
    return 0


def freefix(args):
    run, wt, project = ctx(args)
    ch = [p for p, s in changed(wt, args.base).items() if s != "D" and (wt / p).is_file()]
    for e in ("java", "ui", "go"):
        files = [p for p in ch if eco(p) == e and (e != "java" or p.endswith(".java")) and (e != "ui" or re.search(r"\.(tsx?|css|json)$", p)) and (e != "go" or p.endswith(".go"))]
        if not files:
            continue
        cmd = project["free_fix"][e].replace("{files}", " ".join(f[3:] if e == "ui" else f for f in files))
        r = subprocess.run(cmd, shell=True, cwd=wt, capture_output=True, text=True)
        print(f"FREEFIX {e} exit={r.returncode}")
    return 0


def digest(args):
    run = pathlib.Path(args.run)
    text = pathlib.Path(args.input).read_text(errors="replace") if args.input else sys.stdin.read()
    out_lines = [l for l in text.splitlines() if l.startswith(f"OUTPUT {args.stage}:")] or [l for l in text.splitlines() if l.startswith("ERROR")]
    failing, message, frames = [], "", []
    if args.junit:
        for f in sorted(pathlib.Path(args.junit).glob("TEST-*.xml")):
            for tc in ET.parse(f).getroot().iter("testcase"):
                bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
                if bad is not None:
                    failing.append(f"{tc.get('classname', '')}.{tc.get('name', '')}".strip("."))
                    if not message:
                        message = (bad.get("message") or (bad.text or "").strip().splitlines()[0] if (bad.text or "").strip() else "")[:300]
                        frames = [m for m in re.findall(r"at (com\.infenia[^\s(]*\([\w.]+:\d+\))", bad.text or "")][:5]
    cut = False
    if not message:
        message = " | ".join(re.sub(r"^(OUTPUT [\w.-]+:|ERROR [\w/.-]*:)\s*", "", l) for l in out_lines[-5:])
        cut = len(message) > 300
        message = message[:300]
    cls = args.cls or CLASSES.get(args.stage, "infra_error")
    d = {"step_id": args.step, "attempt": args.attempt, "stage": args.stage, "class": cls, "exit_code": args.exit_code, "message": message,
         "failing": failing[:3] + ([f"(+{len(failing) - 3} more)"] if len(failing) > 3 else []), "frames": frames}
    if args.command:
        d["command"] = args.command
    if cut:
        d["truncated"] = True
    # at most 2 KB: shrink the free text first
    while len(json.dumps(d)) > 2048 and (d["message"] or d["frames"] or d["failing"]):
        d["truncated"] = True
        if len(d["message"]) > 60:
            d["message"] = d["message"][:max(60, len(d["message"]) // 2)]
        elif d["frames"]:
            d["frames"] = d["frames"][:-1]
        elif len(d["failing"]) > 1:
            d["failing"] = d["failing"][:-1]
        else:
            break
    ad = run / "steps" / args.step / f"attempt-{args.attempt}"
    ad.mkdir(parents=True, exist_ok=True)
    save(ad / "failure.json", d)
    r = subprocess.run([sys.executable, str(KIT / "scripts" / "validate-schema.py"), "failure-digest", str(ad / "failure.json")], capture_output=True, text=True)
    if r.returncode:
        die("ERROR digest is not valid: " + r.stdout)
    print(json.dumps(d))
    return 0


def event(args):
    run = pathlib.Path(args.run)
    e = {"ts": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "run_id": run.name}
    for kv in args.pairs:
        k, _, v = kv.partition("=")
        e[k] = int(v) if re.fullmatch(r"-?\d+", v) else float(v) if re.fullmatch(r"-?\d+\.\d+", v) else v
    if args.claude_result:
        r = load(args.claude_result)
        u = r.get("usage") or {}
        e.update(tokens_in=u.get("input_tokens", 0), tokens_out=u.get("output_tokens", 0), cache_read_tokens=u.get("cache_read_input_tokens", 0),
                 cache_write_tokens=u.get("cache_creation_input_tokens", 0), cost_usd=r.get("total_cost_usd", 0), duration_ms=r.get("duration_ms", 0))
    with open(run / "events.jsonl", "a") as f:
        f.write(json.dumps(e, sort_keys=True) + "\n")
    return 0


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("pack-step", "scope-check", "regen", "digest"):
        s = sub.add_parser(name); s.add_argument("run"); s.add_argument("step")
    s = sub.add_parser("free-fix"); s.add_argument("run")
    s = sub.add_parser("event"); s.add_argument("run"); s.add_argument("pairs", nargs="*"); s.add_argument("--claude-result")
    for name, s in sub.choices.items():
        if name in ("pack-step", "scope-check", "regen", "free-fix"):
            s.add_argument("--worktree", default=".")
        if name in ("scope-check", "regen", "free-fix"):
            s.add_argument("--base", required=True)
    sub.choices["pack-step"].add_argument("--attempt", type=int, default=1)
    d = sub.choices["digest"]
    d.add_argument("--attempt", type=int, required=True); d.add_argument("--stage", required=True); d.add_argument("--class", dest="cls")
    d.add_argument("--input"); d.add_argument("--junit"); d.add_argument("--exit-code", type=int, default=1); d.add_argument("--command")
    a = p.parse_args()
    return {"pack-step": pack, "scope-check": scope, "regen": regen, "free-fix": freefix, "digest": digest, "event": event}[a.cmd](a) or 0


sys.exit(main())
