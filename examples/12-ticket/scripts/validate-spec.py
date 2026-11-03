#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit validate-spec RUN_DIR [--worktree W]: design 8.1 and the sizing lint of 11.3.

Reads spec/spec.json, routing.json, ticket.json and project.json from RUN_DIR; the checks that need files run in the worktree
(default: the one in RUN_DIR/worktree, else the current directory). Prints ERROR / WARN lines, writes spec/validation.json and, when
there is no error, spec/spec.hash. Exit 0 = valid, 1 = errors. A valid spec may raise routing.json's risk (high-risk paths)."""
import argparse, hashlib, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import GUARD_PATHS, KIT, RISK, die, git, load, matches, max_risk, model_table, module_of, save

MAX_STEPS = {"S": 2, "M": 5, "L": 8}


def template_re(t):
    # a placeholder is one word without shell metacharacters, so a command cannot smuggle a second command in
    parts = re.split(r"\{[a-z_]+\}", t)
    return re.compile(r"[^\s;&|<>$`'\"\\]+".join(re.escape(x) for x in parts) + r"\Z")


def patch_files(text):
    return {m.group(1) for m in re.finditer(r"^\+\+\+ b/(\S+)", text, re.M)} | {m.group(1) for m in re.finditer(r"^--- a/(\S+)", text, re.M)}


def gradle_deps(wt, module):
    f = wt / module / "build.gradle.kts"
    return {m.group(1) for m in re.finditer(r'project\("(:[^"]+)"\)', f.read_text())} if f.is_file() else set()


def run(a):
    errors, warnings = [], []
    err = lambda m: errors.append(m)
    warn = lambda m: warnings.append(m)
    runp = pathlib.Path(a.run)
    wt = pathlib.Path(a.worktree or (runp / "worktree" if (runp / "worktree").exists() else "."))
    spec_file = runp / "spec" / "spec.json"
    routing, ticket, project = load(runp / "routing.json"), load(runp / "ticket.json"), load(runp / "project.json")
    # 1 schema
    r = subprocess.run([sys.executable, str(KIT / "scripts" / "validate-schema.py"), "ticket-spec", str(spec_file)], capture_output=True, text=True)
    if r.returncode:
        for line in r.stdout.splitlines():
            err("schema " + line)
        return errors, warnings, None  # nothing below is safe on a spec that does not parse
    spec = load(spec_file)
    if spec["ticket_id"] != ticket["ticket_id"]:
        err(f"ticket_id {spec['ticket_id']} is not the ticket's {ticket['ticket_id']}")
    steps = {s["id"]: s for s in spec["steps"]}
    # 2 graph
    if len(steps) != len(spec["steps"]):
        err("duplicate step ids")
    for s in spec["steps"]:
        for d in s["depends_on"]:
            if d not in steps:
                err(f"{s['id']} depends on unknown {d}")
    deps = {i: set(s["depends_on"]) & set(steps) for i, s in steps.items()}
    anc = {}

    def ancestors(i, seen=()):
        if i in anc:
            return anc[i]
        if i in seen:
            err(f"dependency cycle through {i}"); return set()
        out = set()
        for d in deps[i]:
            out |= {d} | ancestors(d, seen + (i,))
        anc[i] = out
        return out
    for i in steps:
        ancestors(i)
    # scaffold
    sp = runp / spec["scaffold_patch"] if not pathlib.Path(spec["scaffold_patch"]).is_absolute() else pathlib.Path(spec["scaffold_patch"])
    scaffold_files = set()
    if not sp.is_file():
        err(f"scaffold_patch {spec['scaffold_patch']} does not exist")
    else:
        text = sp.read_text()
        scaffold_files = patch_files(text)
        if text.strip() and git("apply", "--check", str(sp), cwd=wt, check=False).returncode:
            err("scaffold_patch does not apply cleanly to the checkout")
    sc = spec["scope"]
    locked = set(spec["locked_files"])
    allow_arch = spec["tests_mode"] == "architect_code"
    all_writes = {f for s in spec["steps"] for f in s["write_files"]}
    # 6 scaffold touches only stubs
    for f in sorted(scaffold_files):
        if f in locked and not allow_arch:
            err(f"scaffold touches locked file {f}")
        elif f not in locked and f not in all_writes:
            err(f"scaffold touches {f}, which no step writes (stubs only)")
    guard_ok = ticket.get("allow_guard_paths", False)
    risk = max_risk(spec["risk"], routing["risk"])
    templates = [template_re(t) for t in project["commands"].values()]
    test_templates = [template_re(project["commands"][k]) for k in ("test_class", "go_test", "ui_test") if k in project["commands"]]
    # 3 per step
    for s in spec["steps"]:
        i = s["id"]
        for f in s["write_files"]:
            if not matches(f, sc["allowed_globs"]):
                err(f"{i}: write_files {f} is outside scope.allowed_globs")
            if matches(f, sc["forbidden_globs"]):
                err(f"{i}: write_files {f} is in forbidden_globs")
            if f in locked:
                err(f"{i}: write_files {f} is a locked file")
            if matches(f, GUARD_PATHS) and not guard_ok:
                err(f"{i}: scope_violation: {f} is a guard path and the issue is not about build or dogfood")
            if matches(f, project["denied_write"]):
                err(f"{i}: write_files {f} may not be edited by an agent")
            if matches(f, project["high_risk_paths"]):
                risk = "high"
        if len(s["write_files"]) > s["limits"]["max_files"]:
            err(f"{i}: {len(s['write_files'])} write_files exceed limits.max_files {s['limits']['max_files']}")
        created = set(scaffold_files) | {f for d in ancestors(i) for f in steps[d]["write_files"]}
        for rf in s["read_files"]:
            path = wt / rf["path"]
            if not path.is_file() and rf["path"] not in created:
                err(f"{i}: read_files {rf['path']} does not exist and nothing creates it")
            elif rf.get("lines") and path.is_file():
                m = re.fullmatch(r"(\d+)-(\d+)", rf["lines"])
                n = len(path.read_text(errors="replace").splitlines())
                if not m or int(m[1]) < 1 or int(m[1]) > int(m[2]) or int(m[2]) > n:
                    err(f"{i}: read_files {rf['path']} lines {rf['lines']} not within 1-{n}")
        # 4 commands
        for c in s["done_when"]["commands"]:
            if not any(t.match(c) for t in templates):
                err(f"{i}: done_when command is not a project command template: {c}")
        if len({module_of(f) for f in s["write_files"]}) > 1:
            err(f"{i}: step touches more than one module: {sorted({module_of(f) for f in s['write_files']})}")
        if not s["covers"] and not any(t.match(c) for c in s["done_when"]["commands"] for t in test_templates):
            err(f"{i}: not independently verifiable: no covers and no test command in done_when")
        if not 30 <= s["limits"]["max_diff_lines"] <= 150:
            warn(f"{i}: max_diff_lines {s['limits']['max_diff_lines']} is outside the 30-150 target")
        for d in s["depends_on"]:
            if d in steps and set(s["write_files"]) <= set(steps[d]["write_files"]) and s["limits"]["max_diff_lines"] < 20:
                err(f"{i}: tiny step in the same files as {d}: merge it into {d}")
    # 5 acceptance criteria
    for c in spec["acceptance_criteria"]:
        v = c["verification"]
        if v["kind"] == "command" and (not v.get("command") or not any(t.match(v["command"]) for t in templates)):
            err(f"{c['id']}: command is missing or not a project command template")
    # 7 risk override (a write_file in high_risk_paths raised `risk` above)
    if RISK.index(risk) > RISK.index(routing["risk"]):
        routing.update(risk=risk, allow_architect_code=True, models=model_table(routing["complexity"], risk, routing["tier_mode"], project.get("implementer_override")),
                       tests_mode=routing["tests_mode"])
        warn(f"risk raised to {risk} (spec or high-risk paths); routing.json updated")
    # 8 tests
    if spec["tests_mode"] != routing["tests_mode"]:
        if not (allow_arch and routing.get("allow_architect_code") and any(c["critical"] for c in spec["test_cases"])):
            err(f"tests_mode {spec['tests_mode']} differs from routing.tests_mode {routing['tests_mode']}")
    cases = [c["id"] for c in spec["test_cases"]]
    if len(cases) != len(set(cases)):
        err("duplicate test case ids")
    if len(cases) > project["tests"]["max_cases_total"]:
        err(f"{len(cases)} test cases exceed max_cases_total {project['tests']['max_cases_total']}")
    covered = {c for s in spec["steps"] for c in s["covers"]}
    for c in sorted(set(cases) - covered):
        err(f"test case {c} is covered by no step")
    for c in sorted(covered - set(cases)):
        err(f"a step covers unknown test case {c}")
    # 9 parallel safety
    ids = list(steps)
    for x in range(len(ids)):
        for y in range(x + 1, len(ids)):
            a_, b_ = ids[x], ids[y]
            if a_ in anc[b_] or b_ in anc[a_]:
                continue
            both = set(steps[a_]["write_files"]) & set(steps[b_]["write_files"])
            if both:
                err(f"{a_} and {b_} are not ordered but both write {sorted(both)}")
    # 10 sizing, module order
    if len(steps) > MAX_STEPS[routing["complexity"]]:
        err(f"{len(steps)} steps exceed the {MAX_STEPS[routing['complexity']]} allowed for complexity {routing['complexity']}")
    gm = lambda s: ":" + ":".join(module_of(s["write_files"][0]).split("/")) if s["write_files"] else ""
    for i, s in steps.items():
        for d in anc[i]:
            if gm(s) and gm(steps[d]) and gm(steps[d]) != gm(s) and gm(s) in gradle_deps(wt, gm(steps[d])[1:].replace(":", "/")):
                err(f"{i} ({gm(s)}) runs after {d} ({gm(steps[d])}), which depends on it: order the provider first")
    return errors, warnings, (spec, routing)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run")
    p.add_argument("--worktree")
    a = p.parse_args()
    errors, warnings, ok = run(a)
    for e in errors:
        print("ERROR " + e)
    for w in warnings:
        print("WARN " + w)
    runp = pathlib.Path(a.run)
    save(runp / "spec" / "validation.json", {"errors": errors, "warnings": warnings})
    if ok and not errors:
        spec, routing = ok
        save(runp / "routing.json", routing)
        (runp / "spec" / "spec.hash").write_text("sha256:" + hashlib.sha256((runp / "spec" / "spec.json").read_bytes()).hexdigest() + "\n")
    print(f"RESULT validate-spec errors={len(errors)} warnings={len(warnings)}")
    sys.exit(1 if errors else 0)


main()
