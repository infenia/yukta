#!/usr/bin/env python3
"""Counts `claude_code.skill_activated` events (OpenTelemetry logs) per skill and trigger, and lists the project skills with none.

  OTEL_LOGS_EXPORTER=console ... claude ... > session.log     (or any text export of the events)
  python3 .claude/hooks/skill-usage.py session.log [more.log ...]      (or: ... | python3 .claude/hooks/skill-usage.py)

Needs OTEL_LOG_TOOL_DETAILS=1 when the events were recorded, else user and plugin skills show as `custom_skill`.
See .claude/hooks/README.md.
"""
import collections
import re
import sys
from pathlib import Path

NAME = re.compile(r'skill\.name"?:\s*"?([\w:.-]+)')
TRIGGER = re.compile(r'invocation_trigger"?:\s*"?([\w-]+)')


def main(argv):
    text = "".join(Path(a).read_text(errors="replace") for a in argv) if argv else sys.stdin.read()
    seen = collections.Counter()
    # a skill_activated event carries skill.name and, after it, invocation_trigger; count a name only when its trigger comes before the next name
    names = list(NAME.finditer(text))
    for i, m in enumerate(names):
        end = names[i + 1].start() if i + 1 < len(names) else m.end() + 1500
        t = TRIGGER.search(text[m.end():end])
        if t:
            seen[(m.group(1), t.group(1))] += 1
    for (name, trig), c in sorted(seen.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"{c:5d}  {name}  ({trig})")
    root = Path(__file__).resolve().parents[2]
    used = {n for n, _ in seen}
    unused = sorted(p.parent.name for p in (root / ".claude/skills").glob("*/SKILL.md") if p.parent.name not in used)
    print("project skills with no activation in this data: " + (", ".join(unused) or "none"))


if __name__ == "__main__":
    main(sys.argv[1:])
