# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Per-page illustrations for docs/concepts/*.mdx, drawn with the kit in scenes.py.

Run through generate.py (it calls main()), or alone:  python3 docs/images/diagrams/gen_concepts.py
Each function returns a Scene; main() saves them all as concept-<slug>.{light,dark}.svg.
"""

from generate import mix
from scenes import Scene, chip, icon, panel, text


def band(x, y, w, h, t, label, color=None, lx=None, ly=None):
    """A dashed region that groups elements, with a small label."""
    c = color or t["terminal"]
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{mix(c, t["bg"], 0.07)}" '
            f'stroke="{t["outline"]}" stroke-opacity=".55" stroke-dasharray="5 5"/>'
            + text(lx if lx is not None else x + 14, ly if ly is not None else y + h - 12, label, t,
                   size=14, weight=500, fill=t["muted_fg"]))


# ------------------------------------------------------------------ how-it-works: ports choose the arrow

def message_ports():
    d = Scene("concept-message-ports",
              "One step has three exits, success, failure and error, and each exit is followed by "
              "its own arrow: the branching lives on the arrows.", w=580, h=270)
    d.node("t", 10, 104, 126, 60, "trigger", "Trigger", [], "play", step=0)
    d.node("r", 156, 104, 146, 60, "processor", "Run tests", [], "code", step=1)
    d.node("p", 392, 22, 180, 56, "terminal", "Publish report", [], "send", step=2)
    d.node("n", 392, 106, 180, 56, "terminal", "Notify the team", [], "users", step=2)
    d.node("e", 392, 190, 180, 56, "terminal", "Dead letter", [], "inbox", step=2)
    d.edge("t", "r")
    d.edge("r", "p", fa=0.2, label="success", label_t=0.62, label_dy=-8)
    d.edge("r", "n", fa=0.5, label="failure", label_t=0.62, label_dy=-8)
    d.edge("r", "e", fa=0.8, tone="danger", label="error", label_t=0.62, label_dy=14)
    return d


# ------------------------------------------------------------------ supervisors: engine proposes, shell disposes

def supervisor_shell():
    def under(t):
        return (band(138, 16, 136, 238, t, "a plugin you pick", lx=146, ly=240)
                + band(288, 16, 294, 238, t, "Yukta enforces", lx=300, ly=240))

    d = Scene("concept-supervisor-shell",
              "A supervisor's engine only proposes a directive; Yukta's shell checks it, and only "
              "an allowed one becomes an audited command, a refused one is recorded.",
              under, w=590, h=270)
    d.node("ev", 8, 104, 112, 60, "trigger", "Events", [], "pulse", step=0)
    d.node("en", 150, 104, 112, 60, "processor", "Engine", [], "spark", step=1)
    d.node("sh", 304, 104, 112, 60, "terminal", "Shell", [], "shield", step=2)
    d.node("ok", 446, 40, 132, 60, "processor", "Command", ["audited"], "check", step=3)
    d.node("no", 446, 140, 132, 60, "terminal", "Refusal", ["recorded"], "gate", step=3)
    d.edge("ev", "en")
    d.edge("en", "sh", kind="ctl")
    d.edge("sh", "ok", fa=0.3)
    d.edge("sh", "no", fa=0.7, tone="danger")
    return d


# ------------------------------------------------------------------ who-decides: a route of tiers

def decision_route():
    d = Scene("concept-decision-route",
              "A request goes first to supervisors for a short time; a confident answer ends it, "
              "otherwise it is handed to a person, who has longer.", w=580, h=290)
    d.node("q", 14, 28, 122, 60, "trigger", "Request", [], "inbox", step=0)
    d.node("t1", 230, 28, 152, 60, "processor", "Supervisors", ["short wait"], "eye", step=1)
    d.node("t2", 230, 204, 152, 60, "terminal", "A person", ["longer wait"], "users", step=2)
    d.node("a", 430, 116, 142, 60, "processor", "Answered", [], "check", step=3)
    d.node("f", 14, 204, 172, 60, "terminal", "Timeout", ["fail or default"], "clock", step=3)
    d.edge("q", "t1")
    d.edge("t1", "a", sa="r", sb="t", fb=0.5, label="confident", label_dy=-10)
    d.edge("t1", "t2", sa="b", sb="t", label="hand up", label_dx=44)
    d.edge("t2", "a", sa="r", sb="b", label="answered", label_dx=10, label_dy=8)
    d.edge("t2", "f", sa="l", sb="r", tone="danger")
    return d


# ------------------------------------------------------------------ failure-model: a message's safety net

def failure_path():
    d = Scene("concept-failure-path",
              "A failing step first retries; when retries run out, you choose to route the message "
              "to the error port, fail the node, or drop the message.", w=590, h=290)
    d.node("p", 10, 124, 124, 60, "processor", "Plugin", [], "puzzle", step=0)
    d.node("o", 210, 40, 124, 56, "terminal", "Output", [], "send", step=1)
    d.node("r", 210, 124, 124, 60, "processor", "Retry", [], "repeat", step=1)
    d.node("ro", 420, 40, 160, 60, "terminal", "Route", ["to error port"], "inbox", step=2)
    d.node("fa", 420, 124, 160, 60, "terminal", "Fail", ["node fails"], "flag", step=2)
    d.node("co", 420, 208, 160, 60, "terminal", "Continue", ["drop message"], "queue", step=2)
    d.edge("p", "o", sa="t", fa=0.5, sb="l", label="ok", label_dx=-6, label_dy=-6, bow=34)
    d.edge("p", "r", tone="danger", label="error", label_dy=-8)
    d.edge("r", "p", sa="b", sb="b", fa=0.5, fb=0.5, kind="ctl", label="again", label_dy=12, bow=34)
    d.edge("r", "ro", fa=0.2)
    d.edge("r", "fa")
    d.edge("r", "co", fa=0.8)

    def over(t):
        return text(420, 28, "when retries run out", t, size=14, weight=500, fill=t["muted_fg"])
    d.over = over
    return d


# ------------------------------------------------------------------ time-limits: five clocks on one timeline

def five_clocks():
    rows = (("Queue wait", "slate", 0, 60, "waits for a slot"),
            ("Run deadline", "trigger", 60, 372, "the whole run, never resets"),
            ("Node deadline", "processor", 110, 340, "first activity to the end"),
            ("Node idle", "processor", 150, 340, "resets on activity"),
            ("Element timeout", "processor", 200, 290, "one attempt"))
    X0, ROW, Y0 = 176, 40, 26

    def under(t):
        out = [panel(12, 12, 556, 246, t)]
        for i, (name, col, a, b, cap) in enumerate(rows):
            y = Y0 + i * ROW
            c = {"slate": t["terminal"], "trigger": t["trigger"], "processor": t["processor"]}[col]
            out.append(text(28, y + 25, name, t, size=15, weight=600))
            out.append(f'<rect x="{X0 + a}" y="{y + 4}" width="{b - a}" height="28" rx="8" '
                       f'fill="{mix(c, t["card"], 0.16)}" stroke="{mix(c, t["card"], 0.6)}"/>')
            tx = X0 + a + (34 if name == "Node idle" else 12)
            if (b - a) > 150:
                out.append(text(tx, y + 24, cap, t, size=14, weight=500, fill=c))
            else:
                out.append(text(X0 + b + 8, y + 24, cap, t, size=14, weight=500, fill=t["muted_fg"]))
        ay = Y0 + 5 * ROW + 6
        out.append(f'<path d="M{X0} {ay}H548" stroke="{t["outline"]}" stroke-width="1.5" '
                   f'marker-end="url(#arrow-outline)"/>')
        out.append(text(X0, ay + 20, "time", t, size=14, weight=500, fill=t["muted_fg"]))
        return "".join(out)

    def over(t):
        # A heartbeat restarts the idle clock: the pulse sits at the start of the idle bar.
        y = Y0 + 3 * ROW
        return icon("pulse", X0 + 150 + 8, y + 12, t["trigger"], scale=1.0, stroke=1.6)

    return Scene("concept-five-clocks",
                 "Five clocks measure different spans of one run: queue wait before it starts, "
                 "the run deadline over all of it, a node deadline, a node idle clock that "
                 "restarts on activity, and an element timeout for one attempt.",
                 under, over, w=580, h=270)


# ------------------------------------------------------------------ sub-workflows: a child run inside a node

def sub_workflow():
    def under(t):
        out = [band(14, 12, 532, 100, t, "parent run", ly=102),
               band(14, 150, 532, 108, t, "child run: its own id, same session", ly=248)]
        return "".join(out)

    d = Scene("concept-sub-workflow",
              "A SUB_WORKFLOW node starts another workflow as a child run, waits for it, and emits "
              "one message with the outcome.", under, w=560, h=270)
    d.node("p1", 34, 34, 106, 50, "trigger", "Start", [], "play", step=0)
    d.node("p2", 190, 34, 160, 50, "processor", "Sub-workflow", [], "child", step=1)
    d.node("p3", 410, 34, 106, 50, "terminal", "Next", [], "flag", step=4)
    d.node("c1", 34, 176, 106, 44, "trigger", "Start", [], "play", step=2)
    d.node("c2", 214, 176, 106, 44, "processor", "Steps", [], "graph", step=3)
    d.node("c3", 410, 176, 106, 44, "terminal", "End", [], "flag", step=3)
    d.edge("p1", "p2")
    d.edge("p2", "p3")
    d.edge("c1", "c2")
    d.edge("c2", "c3")
    d.edge("p2", "c1", sa="b", sb="t", fa=0.2, fb=0.5, kind="ctl", label="starts", label_dx=-30, bow=26)
    d.edge("c3", "p2", sa="t", sb="b", fa=0.5, fb=0.8, kind="ctl", label="outcome", label_dx=34, bow=26)
    return d


# ------------------------------------------------------------------ sessions-and-workflows: the folder

def session_folder():
    def under(t):
        out = [panel(16, 20, 306, 230, t, r=14)]
        out.append(icon("layers", 32, 32, t["terminal"], scale=1.3))
        out.append(text(60, 48, "Session", t, size=18, weight=700))
        for i in range(3):
            x = 34 + i * 96
            # version stack: sheets behind the workflow card
            for k, (dx, dy) in enumerate(((10, -10), (5, -5))):
                out.append(f'<rect x="{x + dx}" y="{98 + dy}" width="80" height="104" rx="9" '
                           f'fill="{t["card"]}" stroke="{t["border"]}"/>')
        return "".join(out)

    def over(t):
        out = []
        for i in range(3):
            x = 34 + i * 96
            out.append(panel(x, 98, 80, 104, t, r=9, stroke=mix(t["processor"], t["card"], 0.5)))
            out.append(f'<rect x="{x}" y="98" width="80" height="4" rx="2" fill="{t["processor"]}"/>')
            out.append(icon("graph", x + 24, 116, t["processor"], scale=1.9))
            out.append(text(x + 40, 168, "Workflow", t, size=14, weight=600, anchor="middle"))
            c, w = chip(x + 24, 174, f"v{i + 2}", t, t["terminal"], size=13, pad=7)
            out.append(c)
        out.append(text(34, 238, "newest 20 versions kept", t, size=14, weight=500,
                        fill=t["muted_fg"]))
        # What a session shares: a budget and a key.
        for j, (ic, label, col) in enumerate((("sliders", "Fair share", t["trigger"]),
                                              ("shield", "Key access", t["processor"]))):
            y = 60 + j * 90
            out.append(panel(352, y, 114, 70, t))
            out.append(f'<rect x="364" y="{y + 12}" width="34" height="34" rx="9" '
                       f'fill="{mix(col, t["card"], t["tint_mix"])}"/>')
            out.append(icon(ic, 373, y + 21, col, scale=1.0))
            out.append(text(409, y + 58, label, t, size=14, weight=600, anchor="middle"))
            out.append(f'<path d="M322 {y + 35}H352" stroke="{t["outline"]}" stroke-width="1.5" '
                       'stroke-dasharray="5 5" class="march"/>')
        return "".join(out)

    return Scene("concept-session-folder",
                 "A session is a folder of workflows, each with a stack of versions; the session "
                 "is also the unit that shares a concurrency budget and an API key's reach.",
                 under, over)


# ------------------------------------------------------------------ security-model: key -> role

def key_roles():
    STAIRS = ((10, 126), (194, 84), (378, 42))

    def under(t):
        step = mix(t["terminal"], t["bg"], 0.10)
        edge = mix(t["terminal"], t["bg"], 0.38)
        return "".join(
            f'<rect x="{x}" y="{y + 70}" width="168" height="{256 - (y + 70)}" rx="10" fill="{step}" '
            f'stroke="{edge}" stroke-dasharray="3 5"/>' for x, y in STAIRS)

    def over(t):
        out = []
        for (x, y), n in zip(STAIRS, ("reads", "+ operates", "+ defines")):
            out.append(text(x + 84, y + 106, n, t, size=15, weight=600,
                            fill=mix(t["terminal"], t["bg"], 0.8), anchor="middle"))
        return "".join(out)

    d = Scene("concept-key-roles",
              "An API key has one of three roles, each adding power to the last: a viewer reads, an "
              "operator also starts and steers runs, and only an admin defines workflows.", under, over, w=556, h=270)
    d.node("v", 10, 126, 168, 60, "terminal", "VIEWER", ["read only"], "eye", step=0)
    d.node("o", 194, 84, 168, 60, "processor", "OPERATOR", ["start and steer"], "play", step=1)
    d.node("a", 378, 42, 168, 60, "trigger", "ADMIN", ["define workflows"], "wrench", step=2)
    d.edge("v", "o", sa="t", sb="l", fa=0.5, fb=0.5)
    d.edge("o", "a", sa="t", sb="l", fa=0.5, fb=0.5)
    return d


# ------------------------------------------------------------------ ai-and-deterministic-work: which step type

def step_types():
    d = Scene("concept-step-types",
              "Start from the task: an exact rule uses a deterministic node, choosing among known "
              "options uses a decision node, an open task uses an AI step, and anything from the "
              "last two that is hard to undo goes to a person.", w=620, h=270)
    d.node("q1", 10, 24, 170, 62, "trigger", "Exact rule", ["same answer"], "list", step=0)
    d.node("q2", 10, 104, 170, 62, "trigger", "Known options", ["pick one"], "branch", step=0)
    d.node("q3", 10, 184, 170, 62, "trigger", "Open task", ["write or plan"], "spark", step=0)
    d.node("s1", 225, 24, 190, 62, "processor", "Deterministic", ["script, HTTP, SQL"], "code", step=1)
    d.node("s2", 225, 104, 190, 62, "processor", "Decision", ["CLASSIFY, GATE"], "gate", step=1)
    d.node("s3", 225, 184, 190, 62, "processor", "AI step", ["LLM_CALL, tools"], "spark", step=1)
    d.node("p", 486, 104, 124, 62, "terminal", "Person", ["decides"], "users", step=2)
    d.edge("q1", "s1")
    d.edge("q2", "s2")
    d.edge("q3", "s3")
    d.edge("s2", "p", fb=0.5, label="risky", label_t=0.5)
    d.edge("s3", "p", sa="r", sb="b", fb=0.5, bow=60)
    return d


def ai_fences(): 
    d = Scene("concept-ai-fences",
              "Three fences around one AI step: a deterministic gate checks what it proposes, a "
              "person approves what is risky, and a supervisor beside the graph watches both.",
              w=620, h=270)
    d.node("s", 232, 14, 156, 60, "terminal", "Supervisor", ["watches"], "eye", step=0)
    d.node("a", 10, 108, 128, 62, "processor", "AI", ["proposes"], "spark", step=1)
    d.node("g", 168, 108, 128, 62, "processor", "Gate", ["checks"], "shield", step=2)
    d.node("t", 326, 108, 128, 62, "processor", "Tool", ["acts"], "wrench", step=3)
    d.node("p", 484, 108, 126, 62, "terminal", "Person", ["if risky"], "users", step=4)
    d.node("x", 160, 202, 144, 58, "terminal", "Parked", ["dead letter"], "inbox")
    d.edge("s", "a", sa="b", sb="t", kind="ctl")
    d.edge("s", "t", sa="b", sb="t", kind="ctl")
    d.edge("a", "g")
    d.edge("g", "t")
    d.edge("t", "p")
    d.edge("g", "x", sa="b", sb="t", fa=0.5, fb=0.5, label="fail", tone="danger", label_dx=30)
    return d


# ------------------------------------------------------------------ ai-assisted-configuration: ask, review, apply

def ask_review_apply():
    d = Scene("concept-ask-review-apply",
              "You sit before the first run and again at the end: you ask, the assistant drafts, "
              "you review, the assistant runs it and reads the result. A failed run loops back to "
              "drafting.", w=640, h=250)
    d.node("a", 14, 26, 172, 62, "trigger", "Ask", ["you say the goal"], "users", step=0)
    d.node("d", 234, 26, 172, 62, "processor", "Draft", ["assistant"], "spark", step=1)
    d.node("r", 454, 26, 172, 62, "terminal", "Review", ["you read the JSON"], "eye", step=2)
    d.node("x", 454, 160, 172, 62, "processor", "Run", ["assistant"], "play", step=3)
    d.node("w", 234, 160, 172, 62, "processor", "Read result", ["status, logs"], "list", step=4)
    d.node("o", 14, 160, 172, 62, "terminal", "Done", ["you check it"], "check", step=5)
    d.edge("a", "d")
    d.edge("d", "r")
    d.edge("r", "x", sa="b", sb="t")
    d.edge("x", "w", sa="l", sb="r")
    d.edge("w", "o", sa="l", sb="r", label="done", label_dy=-18)
    d.edge("w", "d", sa="t", sb="b", tone="danger", label="failed", label_dx=26)
    return d


def main():
    for fn in (message_ports, supervisor_shell, decision_route, failure_path, five_clocks,
               sub_workflow, session_folder, key_roles, step_types, ai_fences, ask_review_apply):
        fn().save()


if __name__ == "__main__":
    main()
