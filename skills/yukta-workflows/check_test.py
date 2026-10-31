#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Test of check.py: the real skill passes, and each kind of mistake makes it fail (python3 skills/yukta-workflows/check_test.py)."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def run(mutate):
    tmp = Path(tempfile.mkdtemp())
    try:
        (tmp / "skills/yukta-workflows").mkdir(parents=True)
        (tmp / "docs/interfaces").mkdir(parents=True)
        shutil.copy(HERE / "check.py", tmp / "skills/yukta-workflows/check.py")
        skill = (HERE / "SKILL.md").read_text()
        (tmp / "skills/yukta-workflows/SKILL.md").write_text(mutate(skill))
        shutil.copy(ROOT / "docs/interfaces/mcp.mdx", tmp / "docs/interfaces/mcp.mdx")
        p = subprocess.run([sys.executable, "-I", str(tmp / "skills/yukta-workflows/check.py")], capture_output=True, text=True)
        return p.returncode, p.stdout
    finally:
        shutil.rmtree(tmp)


def must_fail(name, mutate, expect):
    code, out = run(mutate)
    if code == 0 or expect not in out:
        print(f"FAIL {name}: exit {code}, wanted {expect!r}:\n{out}")
        sys.exit(1)


code, out = run(lambda s: s)
if code != 0:
    print("FAIL the real skill must pass:\n" + out)
    sys.exit(1)
must_fail("unknown tool", lambda s: s.replace("| `start_workflow` |", "| `start_workflows` |"), "start_workflows")
must_fail("wrong role", lambda s: s.replace("| `start_workflow` | `OPERATOR` |", "| `start_workflow` | `VIEWER` |"), "role VIEWER here, OPERATOR")
must_fail("unknown argument", lambda s: s.replace("`sessionId`, `executionId` |\n| `get_workflow_history`", "`sessionId`, `executionId`, `payload` |\n| `get_workflow_history`", 1), "argument payload")
must_fail("a tool dropped", lambda s: "\n".join(l for l in s.splitlines() if not l.startswith("| `delete_workflow` |")), "delete_workflow of the docs is missing")
must_fail("a made-up tool in the text", lambda s: s + "\nThen call `restart_everything`.\n", "restart_everything")
must_fail("an unknown action", lambda s: s + "\nUse `EXPLODE`.\n", "EXPLODE")
must_fail("whoami not first", lambda s: s.replace("## First step: `whoami`\n", "## First step\nCall `list_sessions`.\n").replace("Call `whoami` before anything else.", "Call it."), "whoami")
must_fail("confirmation dropped", lambda s: s.replace("`delete_session` and `delete_workflow`, and any call with `force` set", "other things"), "delete_session")
must_fail("a personal path", lambda s: s + "\nSee /home/arun/notes.\n", "personal path")
must_fail("a repo-only command", lambda s: s + "\nRun yk check first.\n", "repo-only command")
must_fail("a credential", lambda s: s + "\nAuthorization: Bearer abcdefghijklmnop1234\n", "credential")
must_fail("name differs from folder", lambda s: s.replace("name: yukta-workflows", "name: yukta", 1), "must equal the folder name")
print("ok: check_test")
