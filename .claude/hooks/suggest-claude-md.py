#!/usr/bin/env python3
"""Opt-in Stop hook: after each turn, hints which CLAUDE.md files or rules may need an update. It never edits anything.

It reads the session transcript (the `transcript_path` of the hook input) and looks for three signals of a gap between the
instructions and the work, no model involved:
  * corrections: user messages that start like one ("no,", "don't", "instead", "actually", ...)
  * blocks: tool calls a hook or rule refused ("Blocked: ...", a denied permission), which the instructions did not prevent
  * edited areas: for the files edited, the module CLAUDE.md and the path-scoped rules that cover them, to re-read against the change

The report goes to $YUKTA_CLAUDE_MD_REPORT_DIR (default ~/.local/state/yukta-dev/claude-md-suggestions)/<session>.md and the user sees one
`systemMessage` line when the number of hints grew. The transcript is read incrementally (the byte offset is kept next to the report), so a
turn costs time in proportion to what is new, a few milliseconds. It exits 0 on every error: a hook must never disturb a session.

Enable it in .claude/settings.local.json (see .claude/hooks/README.md). Off for headless fixers (YUKTA_HEAL_FIXER) and with
YUKTA_CLAUDE_MD_REVIEW=off.
"""
import fnmatch
import json
import os
import re
import sys
from pathlib import Path

CUE = re.compile(r"^\s*(no\b|nope\b|don'?t\b|do not\b|stop\b|instead\b|actually\b|wrong\b|that'?s not\b|not that\b|why did you\b|"
                 r"you should\b|you shouldn'?t\b|never\b)", re.I)
WRAPPED = ("<local-command", "<command-", "<system-reminder", "<task-notification", "[Request interrupted", "Caveat:")
BLOCK = re.compile(r"(Blocked:|has been denied|permission to use .* has been denied|blocked by)", re.I)
BUILD = re.compile(r"(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|Makefile|package\.json|pnpm-lock\.yaml|go\.mod|"
                   r"tsconfig[^/]*\.json|vite\.config\.ts)$|^gradle/|^build-logic/")
MAX_ITEMS = 8


def state_dir():
    base = os.environ.get("YUKTA_CLAUDE_MD_REPORT_DIR") or os.path.join(
        os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state"), "yukta-dev", "claude-md-suggestions")
    Path(base).mkdir(parents=True, exist_ok=True)
    return Path(base)


def first_line(text, n=140):
    text = next((l.strip() for l in text.strip().splitlines() if l.strip()), "")
    return text if len(text) <= n else text[: n - 1] + "…"


def user_text(msg):
    c = msg.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(b.get("text", "") for b in c if isinstance(b, dict) and b.get("type") == "text")
    return ""


def scan(path, offset, st):
    """Adds what the transcript has after `offset` to st; returns the new offset (only whole lines are consumed)."""
    size = os.path.getsize(path)
    if size < offset:  # rewritten (compacted): start again
        offset, st["corrections"], st["blocks"], st["edited"] = 0, [], [], {}
    with open(path, "rb") as f:
        f.seek(offset)
        data = f.read()
    end = data.rfind(b"\n") + 1
    for line in data[:end].splitlines():
        # cheap filter before the JSON parse: only the lines that can matter
        if b'"user"' not in line and b'"assistant"' not in line:
            continue
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if d.get("isSidechain") or d.get("isMeta"):
            continue
        msg = d.get("message") or {}
        if d.get("type") == "assistant":
            for b in msg.get("content") or []:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
                    fp = (b.get("input") or {}).get("file_path") or (b.get("input") or {}).get("notebook_path")
                    if fp:
                        st["edited"][fp] = st["edited"].get(fp, 0) + 1
        else:
            content = msg.get("content")
            if isinstance(content, list):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result" and b.get("is_error"):
                        t = b.get("content")
                        t = t if isinstance(t, str) else " ".join(x.get("text", "") for x in t or [] if isinstance(x, dict))
                        t = re.sub(r"</?tool_use_error>", "", t)
                        if BLOCK.search(t) and first_line(t) not in st["blocks"]:
                            st["blocks"].append(first_line(t))
            text = user_text(msg)
            if text and not text.lstrip().startswith(WRAPPED) and CUE.match(text) and first_line(text) not in st["corrections"]:
                st["corrections"].append(first_line(text))
    return offset + end


def rule_globs(rule):
    try:
        head = rule.read_text().split("---", 2)
    except OSError:
        return []
    if len(head) < 3 or not head[0].strip() == "":
        return []
    return [m.strip().strip("\"'") for m in re.findall(r"^\s*-\s*(.+)$", head[1], re.M)]


def covering_docs(root, edited):
    """module CLAUDE.md files and path-scoped rules that cover the edited files, as paths relative to root."""
    docs, build = {}, False
    for fp in edited:
        try:
            rel = Path(fp).resolve().relative_to(root.resolve())
        except ValueError:
            continue
        rels = rel.as_posix()
        build = build or bool(BUILD.search(rels))
        parts = rel.parts
        for depth in range(len(parts) - 1, 0, -1):  # nearest module folders first
            d = root.joinpath(*parts[:depth])
            md = d / "CLAUDE.md"
            if md.is_file():
                docs.setdefault(md.relative_to(root).as_posix(), 0)
                docs[md.relative_to(root).as_posix()] += 1
            for rule in sorted((d / ".claude" / "rules").glob("*.md")) if (d / ".claude" / "rules").is_dir() else []:
                inside = "/".join(parts[depth:])
                if any(fnmatch.fnmatch(inside, g.replace("**/", "*/").replace("**", "*")) or fnmatch.fnmatch(inside, g) for g in rule_globs(rule)):
                    docs.setdefault(rule.relative_to(root).as_posix(), 0)
                    docs[rule.relative_to(root).as_posix()] += 1
    if build:
        docs.setdefault("CLAUDE.md (Global Commands: a build file changed)", 0)
    return docs


def main():
    try:
        inp = json.load(sys.stdin)
    except ValueError:
        return
    if os.environ.get("YUKTA_HEAL_FIXER") or os.environ.get("YUKTA_CLAUDE_MD_REVIEW") == "off" or inp.get("stop_hook_active"):
        return
    path, sid = inp.get("transcript_path"), inp.get("session_id") or "unknown"
    if not path or not os.path.isfile(path):
        return
    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or inp.get("cwd") or ".")
    sd = state_dir()
    sf = sd / f"{sid}.state.json"
    try:
        st = json.loads(sf.read_text())
    except (OSError, ValueError):
        st = {}
    st.setdefault("offset", 0)
    st.setdefault("corrections", [])
    st.setdefault("blocks", [])
    st.setdefault("edited", {})
    st.setdefault("announced", 0)
    st["offset"] = scan(path, st["offset"], st)
    docs = covering_docs(root, st["edited"])
    n = min(len(st["corrections"]), MAX_ITEMS) + min(len(st["blocks"]), MAX_ITEMS) + len(docs)
    if n:
        lines = ["# CLAUDE.md review hints", "", f"Session `{sid}`. Suggestions only; nothing was edited.", ""]
        if st["corrections"]:
            lines += ["## You corrected Claude: should a CLAUDE.md or rule say it?", ""]
            lines += [f"- {c}" for c in st["corrections"][-MAX_ITEMS:]] + [""]
        if st["blocks"]:
            lines += ["## Calls that were blocked: do the instructions name the allowed way?", ""]
            lines += [f"- {b}" for b in st["blocks"][-MAX_ITEMS:]] + [""]
        if docs:
            lines += ["## Edited areas: re-read these against the change", ""]
            lines += [f"- `{d}`" + (f" ({c} edit(s) under it)" if c else "") for d, c in docs.items()] + [""]
        tmp = sd / f"{sid}.md.tmp"
        tmp.write_text("\n".join(lines))
        tmp.replace(sd / f"{sid}.md")
    if n > st["announced"] and (st["corrections"] or st["blocks"]):
        # only corrections and blocks announce themselves: edits alone would speak every turn
        st["announced"] = n
        print(json.dumps({"systemMessage": f"CLAUDE.md review: {len(st['corrections'])} correction(s), {len(st['blocks'])} block(s) "
                                          f"in this session; hints in {sd / (sid + '.md')}"}))
    elif n > st["announced"]:
        st["announced"] = n
    tmp = sf.with_suffix(".tmp")
    tmp.write_text(json.dumps(st))
    tmp.replace(sf)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # never disturb a session
        print(f"suggest-claude-md: {type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(0)
