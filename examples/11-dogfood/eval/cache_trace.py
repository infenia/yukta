#!/usr/bin/env python3
"""Per-turn prompt-cache trace of the headless Claude sessions of an eval run or an issue worktree.

  python3 -I cache_trace.py B1                       # run B1 of the default EVAL_DIR
  python3 -I cache_trace.py B1 --summary             # one line per session, only the turns that re-wrote context
  python3 -I cache_trace.py /path/to/runs/B1         # a run directory
  python3 -I cache_trace.py --worktree ../yukta-issue-1234 --json   # one issue worktree, machine-readable

It reads the session transcripts Claude Code keeps for the worktree (arm C: the implementer and every
fixer share one transcript; a restarted run adds another). After the first turn of a session, 'read' should be
about the whole previous context. A turn that writes a lot and reads far less than the previous turn's context
paid again for context a cache could have served (flagged '<== re-wrote context'). Claude Code logs one entry
per content block, so a turn can appear several times; they are collapsed here. Transcript folder:
$CLAUDE_CONFIG_DIR or ~/.claude, then projects/<worktree path with every non-alphanumeric character replaced by '-'>.

--json prints {"sessions": [{"id", "turns", "write", "read", "tools": {name: count}, "rewrites": [{"at_s",
"gap_s", "write", "read"}]}]}; the improvement loop's analyzer reads it.
"""
import glob
import json
import os
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime


def eval_dir():
    if os.environ.get("EVAL_DIR"):
        return os.environ["EVAL_DIR"]
    here = os.path.dirname(os.path.abspath(__file__))
    top = subprocess.check_output(["git", "-C", here, "rev-parse", "--show-toplevel"], text=True).strip()
    return os.path.join(os.environ.get("YUKTA_DEV_STATE_DIR", os.path.join(top, ".yukta-dev")), "eval")


def transcripts(worktree):
    cfg = os.environ.get("CLAUDE_CONFIG_DIR") or os.path.expanduser("~/.claude")
    folder = os.path.join(cfg, "projects", re.sub(r"[^A-Za-z0-9]", "-", os.path.realpath(worktree)))
    return sorted(glob.glob(folder + "/*.jsonl"))


def read_session(path):
    """Returns (turns, tools): the turns as (time, usage) and the tool_use blocks counted by tool name."""
    turns, seen, tools, tool_ids = [], set(), Counter(), set()
    with open(path) as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            m = e.get("message") or {}
            if e.get("type") != "assistant":
                continue
            for b in m.get("content") or []:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("id") not in tool_ids:
                    tool_ids.add(b.get("id"))
                    tools[b.get("name", "?")] += 1
                    cmd = str((b.get("input") or {}).get("command", "")).lstrip()
                    if b.get("name") == "Bash" and cmd.startswith("codegraph"):
                        tools["Bash:codegraph"] += 1  # a navigation call made through the CLI
            u = m.get("usage")
            if not u or not str(m.get("model", "")).startswith("claude"):
                continue
            key = (m.get("id"), u.get("output_tokens"))
            if key in seen:
                continue
            seen.add(key)
            turns.append((datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00")), u))
    return turns, tools


def analyse(turns):
    """Totals, the turns that read far less than the previous turn's context, and one row per turn."""
    t0, prev, ctx, w_sum, r_sum, rewrites, rows = turns[0][0], None, 0, 0, 0, [], []
    for ts, u in turns:
        w, r = u.get("cache_creation_input_tokens", 0), u.get("cache_read_input_tokens", 0)
        w_sum += w
        r_sum += r
        gap = (ts - prev).total_seconds() if prev else 0
        miss = prev is not None and w > 8000 and r < 0.6 * ctx
        prev, ctx = ts, r + w
        row = {"at_s": round((ts - t0).total_seconds()), "gap_s": round(gap), "write": w, "read": r, "miss": miss}
        rows.append(row)
        if miss:
            rewrites.append({k: row[k] for k in ("at_s", "gap_s", "write", "read")})
    return w_sum, r_sum, rewrites, rows


def main(argv):
    args, summary, as_json, worktree = [], "--summary" in argv, "--json" in argv, None
    it = iter(argv[1:])
    for a in it:
        if a == "--worktree":
            worktree = next(it, None)
        elif not a.startswith("--"):
            args.append(a)
    if worktree is None and not args:
        print(__doc__)
        return 2
    if worktree:
        worktrees, label = [worktree], worktree
    else:
        run = args[0] if os.path.isdir(args[0]) else os.path.join(eval_dir(), "runs", args[0])
        worktrees = sorted(glob.glob(os.path.join(run, "yukta-issue-*"))) or [os.path.join(run, "yukta")]
        label = run
    sessions = []
    for wt in worktrees:
        for path in transcripts(wt):
            turns, tools = read_session(path)
            if not turns:
                continue
            w, r, rewrites, rows = analyse(turns)
            sessions.append({"id": os.path.basename(path)[:8], "turns": len(turns), "write": w, "read": r,
                             "tools": dict(tools), "rewrites": rewrites, "rows": rows})
    if as_json:
        print(json.dumps({"sessions": [{k: v for k, v in s.items() if k != "rows"} for s in sessions]}))
        return 0
    if not sessions:
        print(f"no transcripts for {label} (looked for the worktree under the Claude config dir)", file=sys.stderr)
        return 1
    for s in sessions:
        print(f"== {s['id']}  {s['turns']} turns")
        for row in s["rows"]:
            if not summary or row["miss"]:
                note = "   <== re-wrote context" if row["miss"] else ""
                print(f"  +{row['at_s']:5d}s gap={row['gap_s']:4d}s write={row['write']:>6} read={row['read']:>6}{note}")
        print(f"  total write={s['write']:,} read={s['read']:,} ({100 * s['read'] // max(1, s['write'] + s['read'])}% read)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
