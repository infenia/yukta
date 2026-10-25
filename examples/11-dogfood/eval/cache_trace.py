#!/usr/bin/env python3
"""Per-turn prompt-cache trace of the headless Claude sessions of an eval run.

  python3 -I cache_trace.py B1                 # run B1 of the default EVAL_DIR
  python3 -I cache_trace.py B1 --summary       # one line per session, only the turns that re-wrote context
  python3 -I cache_trace.py /path/to/runs/B1   # a run directory

It reads the session transcripts Claude Code keeps for the run's issue worktree (arm B: the implementer
and every resumed fixer share one transcript; a restarted run adds another). After the first turn of a
session, 'read' should be about the whole previous context. A turn that writes a lot and reads far less than the
previous turn's context paid again for context a cache could have served (flagged '<== re-wrote context'). Claude Code logs one entry per content block, so a turn can
appear several times; they are collapsed here. Transcript folder: $CLAUDE_CONFIG_DIR or ~/.claude, then
projects/<worktree path with every non-alphanumeric character replaced by '-'>.
"""
import glob
import json
import os
import re
import subprocess
import sys
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


def turns_of(path):
    turns, seen = [], set()
    with open(path) as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            m = e.get("message") or {}
            u = m.get("usage")
            if e.get("type") != "assistant" or not u or not str(m.get("model", "")).startswith("claude"):
                continue
            key = (m.get("id"), u.get("output_tokens"))
            if key in seen:
                continue
            seen.add(key)
            turns.append((datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00")), u))
    return turns


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    summary = "--summary" in argv
    if not args:
        print(__doc__)
        return 2
    run = args[0] if os.path.isdir(args[0]) else os.path.join(eval_dir(), "runs", args[0])
    worktrees = sorted(glob.glob(os.path.join(run, "yukta-issue-*"))) or [os.path.join(run, "yukta")]
    found = False
    for wt in worktrees:
        for path in transcripts(wt):
            turns = turns_of(path)
            if not turns:
                continue
            found = True
            t0, prev, w_sum, r_sum, ctx = turns[0][0], None, 0, 0, 0
            print(f"== {os.path.basename(path)[:8]}  {len(turns)} turns")
            for ts, u in turns:
                w, r = u.get("cache_creation_input_tokens", 0), u.get("cache_read_input_tokens", 0)
                w_sum += w
                r_sum += r
                gap = (ts - prev).total_seconds() if prev else 0
                # re-wrote context: the turn read well under the context the previous turn already had
                miss = prev is not None and w > 8000 and r < 0.6 * ctx
                prev, ctx = ts, r + w
                if not summary or miss:
                    note = "   <== re-wrote context" if miss else ""
                    print(f"  +{(ts - t0).total_seconds():5.0f}s gap={gap:4.0f}s write={w:>6} read={r:>6}{note}")
            print(f"  total write={w_sum:,} read={r_sum:,} ({100 * r_sum // max(1, w_sum + r_sum)}% read)")
    if not found:
        print(f"no transcripts for {run} (looked for the worktree under the Claude config dir)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
