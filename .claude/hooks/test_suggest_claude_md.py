#!/usr/bin/env python3
"""Test of suggest-claude-md.py: python3 .claude/hooks/test_suggest_claude_md.py (no Claude, no network, a few seconds)."""
import json, os, subprocess, sys, tempfile, time
from pathlib import Path

HOOK = Path(__file__).with_name("suggest-claude-md.py")
tmp = Path(tempfile.mkdtemp())
proj = tmp / "proj"
(proj / "core/.claude/rules").mkdir(parents=True)
(proj / "core/CLAUDE.md").write_text("# core\n")
(proj / "core/.claude/rules/engine.md").write_text('---\npaths:\n  - "src/**/orchestrator/**"\n---\n# engine\n')
tr = tmp / "t.jsonl"
env = {**os.environ, "YUKTA_CLAUDE_MD_REPORT_DIR": str(tmp / "out"), "CLAUDE_PROJECT_DIR": str(proj)}
env.pop("YUKTA_HEAL_FIXER", None)
env.pop("YUKTA_CLAUDE_MD_REVIEW", None)


def line(**d):
    return json.dumps(d, separators=(",", ":")) + "\n"  # compact, as Claude Code writes it


def run(extra=None):
    t0 = time.time()
    p = subprocess.run([sys.executable, "-I", str(HOOK)], input=json.dumps({"session_id": "s1", "transcript_path": str(tr), "cwd": str(proj), **(extra or {})}),
                       capture_output=True, text=True, env=env)
    assert p.returncode == 0, p.stderr
    return p.stdout.strip(), time.time() - t0


def fail(m):
    print("FAIL", m)
    sys.exit(1)


report = tmp / "out/s1.md"
tr.write_text(
    line(type="user", message={"role": "user", "content": "please add a retry option"})
    + line(type="user", message={"role": "user", "content": "no, use yk gradle, never ./gradlew\nand more"})
    + line(type="user", isMeta=True, message={"role": "user", "content": "no, ignore meta"})
    + line(type="user", message={"role": "user", "content": "<system-reminder>no, ignore</system-reminder>"})
    + line(type="assistant", message={"role": "assistant", "content": [{"type": "tool_use", "name": "Edit", "input": {"file_path": str(proj / "core/src/main/java/a/orchestrator/X.java")}}]})
    + line(type="user", message={"role": "user", "content": [{"type": "tool_result", "is_error": True, "content": "<tool_use_error>Blocked: ./gradlew is not allowed here; use yk gradle</tool_use_error>"}]})
)
out, _ = run()
msg = json.loads(out)["systemMessage"]
if "1 correction(s), 1 block(s)" not in msg or str(report) not in msg:
    fail(f"announce: {out}")
text = report.read_text()
for want in ("no, use yk gradle, never ./gradlew", "Blocked: ./gradlew is not allowed here", "`core/CLAUDE.md`", "`core/.claude/rules/engine.md`"):
    if want not in text:
        fail(f"report lacks {want!r}:\n{text}")
for nope in ("ignore meta", "ignore</system", "more"):
    if nope in text:
        fail(f"report must not have {nope!r}")
if (proj / "core/CLAUDE.md").read_text() != "# core\n":
    fail("a CLAUDE.md was edited")

out, _ = run()
if out:
    fail(f"nothing new: must print nothing, got {out}")
with open(tr, "a") as f:
    f.write(line(type="user", message={"role": "user", "content": "actually, tests go in src/test"}))
    f.write('{"type":"user","message":{"role":"user","content":"no, half a lin')  # a line still being written
out, _ = run()
if "2 correction(s)" not in out:
    fail(f"a new correction must announce: {out}")
out, _ = run()
if out:
    fail("the half-written line must not count twice")

# off switches
for k, v in (("YUKTA_HEAL_FIXER", "1"), ("YUKTA_CLAUDE_MD_REVIEW", "off")):
    e = {**env, k: v, "YUKTA_CLAUDE_MD_REPORT_DIR": str(tmp / "out2")}
    p = subprocess.run([sys.executable, "-I", str(HOOK)], input=json.dumps({"session_id": "s2", "transcript_path": str(tr)}), capture_output=True, text=True, env=e)
    if p.returncode != 0 or p.stdout or (tmp / "out2").exists():
        fail(f"{k} must switch it off")
# never fails a session
p = subprocess.run([sys.executable, "-I", str(HOOK)], input="not json", capture_output=True, text=True, env=env)
if p.returncode != 0:
    fail("bad input must exit 0")

# speed: 30 MB of transcript, then a turn that adds one line
big = tmp / "big.jsonl"
filler = line(type="assistant", message={"role": "assistant", "content": [{"type": "text", "text": "x" * 2000}]})
with open(big, "w") as f:
    for _ in range(15000):
        f.write(filler)
tr = big
env["YUKTA_CLAUDE_MD_REPORT_DIR"] = str(tmp / "out3")
_, first = run({"session_id": "s3"})
with open(big, "a") as f:
    f.write(line(type="user", message={"role": "user", "content": "no, wrong"}))
_, again = run({"session_id": "s3"})
print(f"30 MB first read {first:.2f}s, next turn {again * 1000:.0f} ms")
if again > 0.5:
    fail(f"a turn must be fast, took {again:.2f}s")

# skill-usage.py on a console-exporter style excerpt (the real format, recorded 2026-10-31)
sample = """{
  body: "claude_code.skill_activated",
  attributes: {
    "event.name": "skill_activated",
    "skill.name": "yukta",
    invocation_trigger: "user-slash",
    "skill.source": "projectSettings",
  },
}
{ body: "claude_code.skill_activated", attributes: { "skill.name": "yukta", invocation_trigger: "claude-proactive" } }
{ body: "claude_code.skill_activated", attributes: { "skill.name": "yukta", invocation_trigger: "user-slash" } }
"""
su = Path(__file__).with_name("skill-usage.py")
p = subprocess.run([sys.executable, "-I", str(su)], input=sample, capture_output=True, text=True)
if p.returncode != 0 or "2  yukta  (user-slash)" not in p.stdout or "1  yukta  (claude-proactive)" not in p.stdout or "tests" not in p.stdout.splitlines()[-1]:
    fail(f"skill-usage: {p.stdout}{p.stderr}")
print("ok: test_suggest_claude_md")
