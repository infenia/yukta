#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Lists the distinct failures of one scheduled run, from structured results (never from logs).

Usage: auto-found.py <workflow> [--jobs jobs.json] [--junit dir] [--k6 dir] [--seed-file f]
Prints a JSON list [{key, title, severity, kind, module, summary, repro}] for auto-found.sh.

Sources
  --junit <dir>  JUnit XML (TEST-*.xml, searched recursively): a test that failed and never passed.
                 A jqwik property prints its seed in the failure text; the seed goes into the repro hint.
  --k6 <dir>     k6 summary.json (and the meta.json beside it): every metric with a breached threshold.
  --jobs <file>  GitHub's jobs list of the run. A failed job is listed only when nothing more specific
                 (a test or a metric above) was found, so a red job is never silently dropped.

Key: the first 12 hex digits of sha1(workflow, subject, detail class). The subject is the test or the
metric, the detail class the exception class of a test or the threshold of a metric, or the failed step
of a job. A new random seed or a new run does not change it, so a repeat is the same issue.

Severity (the Severity field of the Roadmap project)
  | Failure                                                                 | Severity | Kind        |
  |-------------------------------------------------------------------------|----------|-------------|
  | k6 stuck_runs or tracker_events_lost (a stuck run, data lost)           | High     | Perf        |
  | any other k6 threshold (success rate, p95, rejected, dropped iterations)| Medium   | Perf        |
  | a test or property that failed and never passed                         | Medium   | Defect      |
  | a failed step named compare/memory/leak/p95 (a regression check)        | Medium   | Perf        |
  | a failed job of a Security, CodeQL or Trivy workflow                    | High     | CI failure  |
  | any other failed or timed-out job (the build or startup is broken)      | High     | CI failure  |
  | a cancelled job                                                         | Low      | CI failure  |
Flaky tests (failed, then passed on retry) are Low and are tracked by flaky-tests.sh.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
HIGH_METRICS = {"stuck_runs", "tracker_events_lost"}
REGRESSION_STEP = re.compile(r"compare|memory|leak|p95", re.I)
SECURITY_WORKFLOW = re.compile(r"security|codeql|trivy", re.I)
SEED = re.compile(r"\bseed\s*[=:]\s*(-?\d+)", re.I)
EXCEPTION = re.compile(r"^([\w$.]+(?:Exception|Error))\b")


def key_of(workflow, subject, detail):
    return hashlib.sha1("\x1f".join((workflow, subject, detail)).encode()).hexdigest()[:12]


def junit_failures(workflow, root):
    spec = importlib.util.spec_from_file_location("junit_failures", HERE / "junit-failures.py")
    junit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(junit)
    found = []
    for (cls, name), v in junit.outcomes(root).items():
        if not v["failed"] or v["passed"]:
            continue
        detail = v["failed"][0]
        exc = (v["types"][0] if v["types"] else "") or (EXCEPTION.match(detail) or [None, ""])[1]
        seed = SEED.search(detail)
        module = v["module"] if v["module"] != "build-logic" else "build"
        short = cls.rsplit(".", 1)[-1]
        found.append({
            "key": key_of(workflow, f"{cls}#{name}", exc),
            "title": f"{workflow}: {short}.{name} fails",
            "severity": "Medium", "kind": "Defect", "module": module or "build",
            "summary": f"`{cls}#{name}` failed and never passed" + (f" (`{exc}`)" if exc else "")
                       + f":\n\n```text\n{detail[:1500]}\n```",
            "repro": (f"`./gradlew :{module}:test --tests '{cls}.{name}'`" if module else f"test `{cls}#{name}`")
                     + (f"; jqwik seed `{seed.group(1)}` (`@Property(seed = \"{seed.group(1)}\")`)" if seed else ""),
        })
    return found


def k6_failures(workflow, root):
    found = {}
    for path in sorted(pathlib.Path(root).rglob("summary.json")):
        try:
            metrics = json.loads(path.read_text())["metrics"]
            meta = json.loads((path.parent / "meta.json").read_text())
        except (OSError, ValueError, KeyError):
            continue
        test = meta.get("test", path.parent.parent.name)
        for name, m in metrics.items():
            breached = [t for t, bad in (m.get("thresholds") or {}).items() if bad]
            if not breached:
                continue
            base = name.split("{", 1)[0]
            f = found.setdefault((test, base), {"tags": [], "breached": breached[0], "meta": meta})
            if "{" in name:
                f["tags"].append(name[len(base):])
        # a threshold breached only on a tagged metric still counts once per base metric
    out = []
    for (test, base), f in found.items():
        meta = f["meta"]
        out.append({
            "key": key_of(workflow, f"{test}:{base}", f["breached"]),
            "title": f"{workflow}: {test} breaks {base} {f['breached']}",
            "severity": "High" if base in HIGH_METRICS else "Medium", "kind": "Perf", "module": "build",
            "summary": f"The k6 `{test}` run on `{meta.get('commit', '?')}` breached `{base}` `{f['breached']}`"
                       + (f" (also on {', '.join(f'`{t}`' for t in f['tags'][:8])})" if f["tags"] else "")
                       + ". The reports are in the run's artifacts (summary.json, container-stats.csv, server.log).",
            "repro": f"`{('RATE=%s ' % meta['rate']) if meta.get('rate') not in (None, 'default') else ''}"
                     f"{('HOLD=%s ' % meta['hold']) if meta.get('hold') not in (None, 'default') else ''}"
                     f"{('DURATION=%s ' % meta['duration']) if meta.get('duration') not in (None, 'default') else ''}"
                     f"perf/run.sh {test}`",
        })
    return out


def job_failures(workflow, jobs):
    out = []
    for job in jobs:
        if job.get("conclusion") not in ("failure", "timed_out", "cancelled"):
            continue
        steps = [s["name"] for s in job.get("steps") or [] if s.get("conclusion") == "failure"]
        step = steps[0] if steps else job["conclusion"]
        if job["conclusion"] == "cancelled":
            severity, kind = "Low", "CI failure"
        elif REGRESSION_STEP.search(step) and not SECURITY_WORKFLOW.search(workflow):
            severity, kind = "Medium", "Perf"
        else:
            severity, kind = "High", "CI failure"
        out.append({
            "key": key_of(workflow, job["name"], step),
            "title": f"{workflow}: {job['name']} fails at {step}",
            "severity": severity, "kind": kind, "module": "build",
            "summary": f"Job [{job['name']}]({job.get('html_url', '')}) {job['conclusion']}"
                       + (f"; failed step: {', '.join(steps)}" if steps else "") + ".",
            "repro": "Re-run the workflow with `workflow_dispatch`, or run the failed step's command locally.",
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("workflow")
    ap.add_argument("--jobs")
    ap.add_argument("--junit")
    ap.add_argument("--k6")
    args = ap.parse_args()
    found = []
    if args.junit and pathlib.Path(args.junit).is_dir():
        found += junit_failures(args.workflow, args.junit)
    if args.k6 and pathlib.Path(args.k6).is_dir():
        found += k6_failures(args.workflow, args.k6)
    if not found and args.jobs:
        found += job_failures(args.workflow, json.loads(pathlib.Path(args.jobs).read_text()).get("jobs", []))
    seen, unique = set(), []
    for f in found:
        if f["key"] not in seen:
            seen.add(f["key"])
            unique.append(f)
    json.dump(unique, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
