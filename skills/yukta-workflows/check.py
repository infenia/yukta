#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Checks skills/yukta-workflows/SKILL.md against docs/interfaces/mcp.mdx (the tool reference): exits 1 with the problems, 0 when it holds.

  python3 skills/yukta-workflows/check.py        (from anywhere; paths are found from this file)

The skill may name only tools, roles, arguments and actions that the docs page has, the same role for each tool, and must cover every tool of the
page. It must tell the agent to call `whoami` first, list the actions that need the user's yes, and hold no credentials, personal paths or
repo-only commands. Stdlib only.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKILL = HERE / "SKILL.md"
DOCS = HERE.parents[1] / "docs/interfaces/mcp.mdx"
ROLES = {"VIEWER", "OPERATOR", "ADMIN"}
TICK = re.compile(r"`([^`]+)`")
# a row of a tool table: | `tool` | `ROLE` | arguments | ...
ROW = re.compile(r"^\|\s*`([a-z]+(?:_[a-z]+)+|whoami)`\s*\|\s*`(VIEWER|OPERATOR|ADMIN)`\s*\|([^|]*)\|")
CONFIRM = ["create_session", "delete_session", "delete_workflow", "STOP_ALL", "rollback_workflow", "set_scheduler_limits", "signal_node", "force"]
FORBIDDEN = [
    (re.compile(r"/(home|Users|media|mnt)/\w"), "a personal path"),
    (re.compile(r"\b(yk|\./gradlew|gradlew|pnpm)\b"), "a repo-only command"),
    (re.compile(r"Bearer\s+[A-Za-z0-9_\-]{16,}"), "a credential"),
    (re.compile(r"\b(sk|ghp|xox[bp])-[A-Za-z0-9]{10,}"), "a credential"),
]


def table(text):
    """tool -> (role, set of backticked argument names) for every tool row of the text."""
    out = {}
    for line in text.splitlines():
        m = ROW.match(line)
        if m:
            out[m.group(1)] = (m.group(2), set(TICK.findall(m.group(3))))
    return out


def main():
    problems = []
    skill, docs = SKILL.read_text(), DOCS.read_text()
    head = re.match(r"---\n(.*?)\n---\n", skill, re.S)
    meta = dict(re.findall(r"^(\w+):\s*(.+)$", head.group(1), re.M)) if head else {}
    if meta.get("name") != SKILL.parent.name:
        problems.append(f"frontmatter name {meta.get('name')!r} must equal the folder name {SKILL.parent.name!r}")
    if not meta.get("description"):
        problems.append("frontmatter has no description")

    ref, mine = table(docs), table(skill)
    if len(ref) < 20:
        problems.append(f"found only {len(ref)} tools in {DOCS.name}: its table format changed, update this check")
    for tool, (role, args) in mine.items():
        if tool not in ref:
            problems.append(f"tool {tool}: not in {DOCS.name}")
            continue
        if role != ref[tool][0]:
            problems.append(f"tool {tool}: role {role} here, {ref[tool][0]} in the docs")
        for a in sorted(args - ref[tool][1]):
            problems.append(f"tool {tool}: argument {a} is not in the docs row ({', '.join(sorted(ref[tool][1])) or 'none'})")
    for tool in sorted(set(ref) - set(mine)):
        problems.append(f"tool {tool} of the docs is missing from the skill's table")

    # every snake_case name or UPPER_CASE word in backticks is a tool, role or action of the docs
    doc_ticks = set(TICK.findall(re.sub(r"```.*?```", "", docs, flags=re.S)))  # fenced blocks would unpair the backticks
    for tok in sorted({t for t in TICK.findall(skill)}):
        if re.fullmatch(r"[a-z]+(?:_[a-z]+)+", tok) and tok not in ref:
            problems.append(f"`{tok}` looks like a tool and is not one in the docs")
        elif re.fullmatch(r"[A-Z][A-Z_]+", tok) and tok not in ROLES and tok not in doc_ticks:
            problems.append(f"`{tok}` (an action or value) is not in the docs")

    body = skill[head.end():] if head else skill
    first = [m.start() for m in re.finditer(r"`whoami`", body)]
    others = [m.start() for t in ref if t != "whoami" for m in re.finditer(rf"`{t}`", body)]
    if not first or (others and min(others) < first[0]):
        problems.append("`whoami` must be the first tool the skill names")
    if "## First step" not in skill:
        problems.append("no 'First step' section naming whoami")
    section = re.search(r"## Ask the user first(.*?)\n## ", skill, re.S)
    if not section:
        problems.append("no 'Ask the user first' section")
    else:
        for word in CONFIRM:
            if word not in section.group(1):
                problems.append(f"'Ask the user first' does not mention {word}")
    for rx, what in FORBIDDEN:
        for m in rx.finditer(skill):
            problems.append(f"the skill holds {what}: {m.group(0)!r}")

    for p in problems:
        print("FAIL", p)
    if problems:
        sys.exit(1)
    print(f"ok: {SKILL.relative_to(HERE.parents[1])} matches {DOCS.relative_to(HERE.parents[1])} ({len(mine)} tools)")


if __name__ == "__main__":
    main()
