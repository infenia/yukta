#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""ticketkit gate-tests RUN_DIR [--worktree W] [--runner CMD --report FORMAT:PATH]  (design 3.3, 8.1b, 15.6)

After the test writer: applies the scaffold stubs (once), checks that only stubs and locked test files changed, that every case has a
[T#] tagged test and no tag is unknown, that the tagged tests compile and each fails with an assertion or "not implemented" (never a
setup, import or syntax error), and that no test is trivial. Then it locks the test files (spec/locked.json: sha256) and writes
spec/test_map.json (case -> tests, built from the report, never from the agent's claim).
tests_mode existing and implementer_writes have no red check up front (exit 0, SKIP). Errors go to stdout and spec/gate.json; exit 1.

--runner CMD (shell, run in the worktree) and --report junit:DIR | vitest:FILE | gotest:FILE | gotest:stdout override the per-ecosystem
defaults (a Gradle, Vitest or Go run through yk, only the tagged test files)."""
import argparse, hashlib, json, pathlib, re, subprocess, sys, xml.etree.ElementTree as ET
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from ticketlib import git, load, module_of, save

TAG = re.compile(r"\[(T\d+)\]")
TRIVIAL = [
    (re.compile(r"assert(True|That)\(\s*true\s*\)(\.isTrue\(\))?|assertEquals\(\s*(\w+)\s*,\s*\3\s*\)|expect\(\s*true\s*\)\.toBe\(\s*true\s*\)"), "always-true assertion"),
    (re.compile(r"@Disabled|\bxit\(|\bxdescribe\(|\b(it|test|describe)\.skip\(|\bt\.Skip\("), "skipped test"),
    (re.compile(r"(void\s+\w+\([^)]*\)\s*(throws\s+[\w., ]+)?\{\s*\}|=>\s*\{\s*\}\s*\)|func\(t \*testing\.T\)\s*\{\s*\})"), "empty test body"),
]
SETUP = re.compile(r"ExceptionInInitializerError|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|SyntaxError|Cannot find module|Failed to resolve import|ReferenceError|is not defined|cannot find package|undefined:|build failed|setup failed", re.I)
RED = re.compile(r"AssertionError|AssertionFailedError|ComparisonFailure|expected|not implemented|UnsupportedOperationException|NotImplemented|toBe|toEqual|Mismatch|got .* want", re.I)


def parse_junit(d):
    out = []
    for f in sorted(pathlib.Path(d).glob("TEST-*.xml")):
        for tc in ET.parse(f).getroot().iter("testcase"):
            bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
            msg = ((bad.get("type") or "") + " " + (bad.get("message") or "") + " " + (bad.text or "")[:400]) if bad is not None else ""
            st = "skipped" if tc.find("skipped") is not None else "fail" if bad is not None else "pass"
            out.append({"name": tc.get("name", ""), "status": st, "message": msg, "error_elem": tc.find("error") is not None and tc.find("failure") is None})
    return out


def parse_vitest(f):
    out = []
    for fr in json.loads(pathlib.Path(f).read_text()).get("testResults", []):
        for t in fr.get("assertionResults", []):
            st = {"passed": "pass", "failed": "fail"}.get(t["status"], "skipped")
            out.append({"name": t.get("fullName") or t.get("title", ""), "status": st, "message": " ".join(t.get("failureMessages", []))[:400], "error_elem": False})
    return out


def parse_gotest(text):
    tests, msgs = {}, {}
    for line in text.splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        n = e.get("Test")
        if not n:
            continue
        if e["Action"] == "output":
            msgs[n] = msgs.get(n, "") + e["Output"]
        elif e["Action"] in ("pass", "fail", "skip"):
            tests[n] = {"name": n, "status": {"pass": "pass", "fail": "fail", "skip": "skipped"}[e["Action"]], "message": msgs.get(n, "")[:400], "error_elem": "panic:" in msgs.get(n, "")}
    return list(tests.values())


def fqcn(path):
    m = re.search(r"src/test/java/(.+)\.java$", path)
    return m.group(1).replace("/", ".") if m else None


def default_runs(locked):
    """[(runner, report)] per ecosystem of the locked files."""
    runs = []
    java = [f for f in locked if f.endswith(".java")]
    for mod in sorted({module_of(f) for f in java}):
        files = [f for f in java if module_of(f) == mod]
        tests = " ".join(f"--tests {fqcn(f)}" for f in files if fqcn(f))
        runs.append((f"yk gradle :{mod.replace('/', ':')}:test {tests}", f"junit:{mod}/build/test-results/test"))
    ui = [f[3:] for f in locked if f.startswith("ui/") and re.search(r"\.(test|spec)\.tsx?$", f)]
    if ui:
        runs.append(("yk tool -C ui npx --no-install vitest run --reporter=json --outputFile=node_modules/.cache/ticket-gate.json " + " ".join(ui), "vitest:ui/node_modules/.cache/ticket-gate.json"))
    if any(f.startswith("cli/") and f.endswith("_test.go") for f in locked):
        runs.append(("yk tool -C cli go test -json ./...", "gotest:stdout"))
    return runs


def execute(wt, runner, report, errors):
    fmt, _, path = report.partition(":")
    target = wt / path
    if fmt == "junit":
        for old in target.glob("TEST-*.xml") if target.is_dir() else []:
            old.unlink()
    elif fmt != "gotest" or path != "stdout":
        target.unlink(missing_ok=True)
    r = subprocess.run(runner, shell=True, cwd=wt, capture_output=True, text=True)
    try:
        if fmt == "junit":
            tests = parse_junit(target)
        elif fmt == "vitest":
            tests = parse_vitest(target)
        else:
            tests = parse_gotest(r.stdout if path == "stdout" else target.read_text())
    except (OSError, ValueError, ET.ParseError):
        tests = []
    if not tests:
        tail = " | ".join((r.stdout + r.stderr).strip().splitlines()[-6:])[:600]
        errors.append(f"compile_or_setup: no test report from `{runner}` (exit {r.returncode}): {tail}")
    return tests


def main():
    a = argparse.ArgumentParser()
    a.add_argument("run"); a.add_argument("--worktree", default="."); a.add_argument("--runner"); a.add_argument("--report")
    o = a.parse_args()
    run, wt = pathlib.Path(o.run), pathlib.Path(o.worktree).resolve()
    spec, routing = load(run / "spec" / "spec.json"), load(run / "routing.json")
    errors, warnings = [], []
    mode = spec["tests_mode"]
    if mode in ("existing", "implementer_writes"):
        print(f"SKIP gate-tests: tests_mode {mode} has no red check up front")
        save(run / "spec" / "gate.json", {"skipped": mode, "errors": []}); return 0
    locked = spec["locked_files"]
    # apply the stubs once; a patch that is already applied (reverse applies) is left alone
    sp = run / spec["scaffold_patch"]
    scaffold = set(re.findall(r"^\+\+\+ b/(\S+)", sp.read_text(), re.M)) if sp.is_file() else set()
    if scaffold and git("apply", "--check", "-R", str(sp), cwd=wt, check=False).returncode:
        r = git("apply", str(sp), cwd=wt, check=False)
        if r.returncode:
            errors.append(f"scaffold does not apply: {r.stderr.strip()[:200]}")
    tp = run / "spec" / "tests.patch"
    if mode == "architect_code" and tp.is_file() and git("apply", "--check", "-R", str(tp), cwd=wt, check=False).returncode:
        git("apply", str(tp), cwd=wt, check=False)
    # only stubs and locked test files may differ from HEAD
    st = git("status", "--porcelain", "--untracked-files=all", cwd=wt).stdout.splitlines()
    changed = {l[3:].split(" -> ")[-1] for l in st}
    for f in sorted(changed - scaffold - set(locked)):
        if not re.search(r"(^|/)(build|node_modules|\.gradle|\.yukta-dev)/", f):  # build output (normally gitignored anyway)
            errors.append(f"scope_violation: {f} was changed; only stubs and locked test files may change in this phase")
    for f in locked:
        if not (wt / f).is_file():
            errors.append(f"locked test file {f} does not exist")
    # tags
    cases = {c["id"] for c in spec["test_cases"]}
    tagged_in_src = {}
    for f in locked:
        p = wt / f
        if p.is_file():
            text = p.read_text(errors="replace")
            for t in set(TAG.findall(text)):
                tagged_in_src.setdefault(t, []).append(f)
            for rx, why in TRIVIAL:
                for m in rx.finditer(text):
                    errors.append(f"trivial test ({why}) in {f}:{text[:m.start()].count(chr(10)) + 1}")
    for c in sorted(cases - set(tagged_in_src)):
        errors.append(f"case {c} has no tagged test")
    for t in sorted(set(tagged_in_src) - cases):
        errors.append(f"tag [{t}] in {tagged_in_src[t]} is not a case of the spec")
    # run and red check
    if not errors:
        runs = [(o.runner, o.report)] if o.runner else default_runs(locked)
        tests = []
        for runner, report in runs:
            tests += execute(wt, runner, report, errors)
        by_case = {}
        for t in tests:
            for c in set(TAG.findall(t["name"])):
                by_case.setdefault(c, []).append(t)
        for t in tests:
            if not TAG.search(t["name"]):
                warnings.append(f"untagged test: {t['name']}")
        if not errors:
            for c in sorted(cases):
                ts = by_case.get(c)
                if not ts:
                    errors.append(f"case {c}: tagged in source but not in the test report (not run)"); continue
                for t in ts:
                    if t["status"] == "pass":
                        errors.append(f"case {c}: `{t['name']}` passes before any implementation (not red)")
                    elif t["status"] == "skipped":
                        errors.append(f"case {c}: `{t['name']}` is skipped")
                    elif SETUP.search(t["message"]) or (t["error_elem"] and not RED.search(t["message"])):
                        errors.append(f"case {c}: `{t['name']}` fails with a setup, import or syntax error, not an assertion: {t['message'][:160].strip()}")
                    elif not RED.search(t["message"]):
                        warnings.append(f"case {c}: `{t['name']}` fails for an unrecognised reason: {t['message'][:120].strip()}")
            save(run / "spec" / "test_map.json", {c: [t["name"] for t in by_case.get(c, [])] for c in sorted(cases)})
    if not errors:
        save(run / "spec" / "locked.json", {f: hashlib.sha256((wt / f).read_bytes()).hexdigest() for f in sorted(locked)})
    save(run / "spec" / "gate.json", {"errors": errors, "warnings": warnings})
    for e in errors:
        print("ERROR " + e)
    for w in warnings:
        print("WARN " + w)
    print(f"RESULT gate-tests errors={len(errors)} warnings={len(warnings)}")
    return 1 if errors else 0


sys.exit(main())
