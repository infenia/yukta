# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""The four illustrations on the docs home page: Learn, Do, Look up and Understand.

Run through generate.py (it calls main()), or alone:  python3 docs/images/diagrams/generate_paths.py

Each scene is a picture of what the reader does on that path, drawn with Yukta's own pieces so the
reader recognises the product in it:
  * Learn:      a staircase of workflow nodes, each a lesson, climbing to a finish flag.
  * Do:         a terminal and a checklist: edit, validate, verify.
  * Look up:    a search box over a reference table of plugins and their output ports.
  * Understand: a supervisor watching a rule step and an AI step, with a dashed control line to each.
Same tokens, node look and motion rules as generate.py; designed to read at about 390px wide.
"""

from generate import mix
from scenes import H, W, Scene, chip, icon, panel, text
import generate as g

# ---------------------------------------------------------------------------- Learn

def learn():
    STAIRS = ((26, 134), (180, 92), (334, 50))  # node x, y: each lesson sits on its own stair

    def under(t):
        step = mix(t["trigger"], t["bg"], 0.10)
        edge = mix(t["trigger"], t["bg"], 0.38)
        return "".join(
            f'<rect x="{x}" y="{y + 64}" width="120" height="{H - 14 - (y + 64)}" rx="10" '
            f'fill="{step}" stroke="{edge}" stroke-dasharray="3 5"/>' for x, y in STAIRS)

    def over(t):
        # The step number sits on the stair under each lesson.
        return "".join(
            text(x + 60, y + 64 + 40, str(n), t, size=30, weight=700,
                 fill=mix(t["trigger"], t["bg"], 0.6), anchor="middle")
            for n, (x, y) in enumerate(STAIRS, 1))

    d = Scene("path-learn",
              "A staircase of three workflow steps, Run, Add AI and Ship, each one higher than "
              "the last, climbing to a finish flag.", under, over)
    d.node("a", 26, 134, 120, 58, "trigger", "Run", [], "play", step=0)
    d.node("b", 180, 92, 120, 58, "processor", "Add AI", [], "spark", step=1)
    d.node("c", 334, 50, 120, 58, "terminal", "Ship", [], "flag", step=2)
    d.edge("a", "b")
    d.edge("b", "c")
    return d


# ---------------------------------------------------------------------------- Do

def do():
    def under(t):
        out = [panel(26, 40, 214, 196, t)]
        # Terminal title bar and window dots.
        out.append(f'<path d="M26 52a12 12 0 0 1 12-12h190a12 12 0 0 1 12 12v18H26z" '
                   f'fill="{mix(t["terminal"], t["card"], 0.14)}"/>')
        for i, c in enumerate((t["danger"], t["trigger"], t["processor"])):
            out.append(f'<circle cx="{44 + i * 15}" cy="55" r="4" fill="{c}" opacity=".85"/>')
        # Command lines: what the reader types, and the reply that proves it worked.
        out.append(text(40, 100, "$", t, size=17, weight=600, fill=t["trigger"], mono=True))
        out.append(text(56, 100, "yukta-cli", t, size=17, weight=600, mono=True))
        out.append(text(56, 124, "session apply", t, size=17, weight=500,
                        fill=t["muted_fg"], mono=True))
        out.append(text(56, 148, "workflow start", t, size=17, weight=500,
                        fill=t["muted_fg"], mono=True))
        out.append(f'<path d="M40 176H228" stroke="{t["border"]}"/>')
        out.append(icon("check", 38, 188, t["processor"], scale=1.25, stroke=1.4))
        out.append(text(64, 204, "SUCCESS", t, size=17, weight=700, fill=t["processor"],
                        mono=True))
        # A blinking cursor: the shell is waiting for you.
        out.append(f'<rect class="live" x="182" y="86" width="9" height="18" rx="2" '
                   f'fill="{t["trigger"]}"/>')
        return "".join(out)

    def over(t):
        # Checklist: the three moves every guide follows.
        out = []
        for i, (label, ic, state) in enumerate((("Edit", "wrench", "done"),
                                                ("Validate", "shield", "done"),
                                                ("Verify", "check", "now"))):
            y = 62 + i * 62
            col = t["processor"] if state == "done" else t["trigger"]
            tint = mix(col, t["card"], t["tint_mix"])
            out.append(panel(262, y, 192, 50, t))
            out.append(f'<rect x="272" y="{y + 9}" width="32" height="32" rx="8" fill="{tint}"/>')
            out.append(icon(ic, 280, y + 17, col, scale=1.0))
            out.append(text(316, y + 31, label, t, size=18, weight=600))
            if state == "done":
                out.append(f'<circle cx="436" cy="{y + 25}" r="9" fill="{t["processor"]}"/>')
                out.append(f'<path d="M431.4 {y + 25.2} l3.2 3.2 6-6.6" fill="none" stroke="#FFFFFF" '
                           'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>')
            else:
                out.append(f'<g class="live"><circle cx="436" cy="{y + 25}" r="9" fill="none" '
                           f'stroke="{t["trigger"]}" stroke-width="2"/></g>')
        return "".join(out)

    return Scene("path-do",
                 "A terminal running yukta-cli commands and ending in SUCCESS, beside a checklist "
                 "of three steps: Edit, Validate and Verify.", under, over)


# ---------------------------------------------------------------------------- Look up

def lookup():
    ROWS = (("VALIDATE", (("default", "ok"), ("invalid", "bad"))),
            ("BRANCH", (("low", "ok"), ("high", "ok"), ("default", "mute"))),
            ("LLM_CALL", (("success", "ok"), ("failure", "bad"))))

    def under(t):
        out = [panel(26, 24, 428, 222, t)]
        # Search box with a typed query and the shortcut chip.
        out.append(f'<rect x="40" y="38" width="400" height="38" rx="19" fill="{t["bg"]}" '
                   f'stroke="{t["border"]}"/>')
        out.append(f'<g transform="translate(58 47)" fill="none" stroke="{t["muted_fg"]}" '
                   'stroke-width="1.7" stroke-linecap="round"><circle cx="7.5" cy="7.5" r="5.5"/>'
                   '<path d="M12 12l4 4"/></g>')
        out.append(text(86, 63, "plugin", t, size=17, weight=500, fill=t["muted_fg"]))
        out.append(f'<rect class="live" x="148" y="48" width="2" height="18" fill="{t["trigger"]}"/>')
        out.append(f'<rect x="378" y="46" width="52" height="22" rx="7" fill="{t["card"]}" '
                   f'stroke="{t["border"]}"/>')
        out.append(text(404, 62, "Ctrl K", t, size=12, weight=500, fill=t["muted_fg"],
                        anchor="middle"))
        return "".join(out)

    def over(t):
        out = []
        tone = {"ok": t["processor"], "bad": t["danger"], "mute": t["terminal"]}
        for i, (name, ports) in enumerate(ROWS):
            y = 92 + i * 50
            hot = i == 1
            if hot:
                out.append(f'<g class="live"><rect x="36" y="{y - 2}" width="408" height="44" '
                           f'rx="10" fill="none" stroke="{t["primary"]}" stroke-width="1.5"/></g>')
                out.append(f'<rect x="38" y="{y}" width="404" height="40" rx="9" '
                           f'fill="{mix(t["primary"], t["card"], t["tint_mix"])}"/>')
            else:
                out.append(f'<path d="M44 {y + 44}H436" stroke="{t["border"]}" '
                           f'stroke-opacity=".7"/>' if i < 2 else "")
            out.append(icon("puzzle", 52, y + 11, t["processor"], scale=1.1))
            out.append(text(80, y + 26, name, t, size=17, weight=700, mono=True))
            x = 232
            for label, kind in ports:
                c, w = chip(x, y + 9, label, t, tone[kind])
                out.append(c)
                x += w + 8
        return "".join(out)

    return Scene("path-lookup",
                 "A search box over a reference table: the plugins VALIDATE, BRANCH and LLM_CALL, "
                 "each listed with its output ports such as default, invalid, success and failure.",
                 under, over)


# ---------------------------------------------------------------------------- Understand

def understand():
    def under(t):
        out = []
        # The supervisor's field of view: a dashed band that frames the two steps it watches.
        out.append(f'<rect x="36" y="146" width="408" height="104" rx="16" '
                   f'fill="{mix(t["terminal"], t["bg"], 0.07)}" stroke="{t["outline"]}" '
                   'stroke-opacity=".55" stroke-dasharray="5 5" class="march"/>')
        out.append(text(52, 240, "a run", t, size=14, weight=500, fill=t["muted_fg"]))
        # Control lines (dashed = commands and events) from the supervisor down to each step.
        for x2 in (146, 340):
            out.append(f'<path d="M240 108 C240 130 {x2} 126 {x2} 160" fill="none" '
                       f'stroke="{t["outline"]}" stroke-width="1.5" stroke-dasharray="5 5" '
                       f'stroke-linecap="round" class="march" marker-end="url(#arrow-outline)"/>')
        return "".join(out)

    def over(t):
        out = []
        # Supervisor: an eye in a shield-coloured pill at the top, the one watching.
        acc = t["terminal"]
        out.append(f'<g class="live"><rect x="152" y="34" width="176" height="64" rx="14" '
                   f'fill="{acc}" opacity=".30" filter="url(#halo)"/></g>')
        out.append(panel(154, 36, 172, 60, t, r=14, stroke=mix(acc, t["card"], 0.5)))
        out.append(f'<rect x="166" y="48" width="36" height="36" rx="9" '
                   f'fill="{mix(acc, t["card"], t["tint_mix"])}"/>')
        out.append(icon("eye", 174, 56, acc, scale=1.25))
        out.append(text(212, 63, "Supervisor", t, size=16, weight=600))
        out.append(text(212, 81, "watches, steers", t, size=12, weight=400, fill=t["muted_fg"]))
        return "".join(out)

    d = Scene("path-understand",
              "A supervisor at the top watches a run through dashed control lines. In the run, a "
              "rule step passes its result to an AI step.", under, over)
    d.node("r", 52, 170, 188, 58, "processor", "Rule", [], "gate", step=0)
    d.node("a", 252, 170, 176, 58, "processor", "AI", [], "spark", step=1)
    d.edge("r", "a", fa=0.5, fb=0.5)
    return d


def main():
    for fn in (learn, do, lookup, understand):
        fn().save()


if __name__ == "__main__":
    main()
