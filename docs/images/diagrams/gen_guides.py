# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Diagrams for the Learn tutorials (`learn-*`) and the build, AI and supervise guides (`guide-*`).

Run through generate.py (it calls main()), or alone:  python3 docs/images/diagrams/gen_guides.py

Each picture shows the thing the page builds or the choice the page helps with, using the node names,
ports and modes the page itself uses. Same kit (scenes.py), tokens and motion as the home page.
"""

import generate as g
from generate import mix
from scenes import Scene, chip, icon, panel, text


def grid(x0, y0, cw, ch, gx, gy):
    return lambda c, r: (x0 + c * (cw + gx), y0 + r * (ch + gy))


def arrow(x1, y1, x2, y2, t, dash=False, tone=None, bend=0):
    col = t["danger"] if tone == "danger" else t["outline"]
    mk = "danger" if tone == "danger" else "outline"
    mx, my = (x1 + x2) / 2, (y1 + y2) / 2 + bend
    d = f"M{x1} {y1} Q{mx} {my} {x2} {y2}" if bend else f"M{x1} {y1} L{x2} {y2}"
    da = ' stroke-dasharray="5 5" class="march"' if dash else ""
    return (f'<path d="{d}" fill="none" stroke="{col}" stroke-width="1.5" stroke-linecap="round"'
            f'{da} marker-end="url(#arrow-{mk})"/>')


def pill(x, y, label, t, color=None, anchor="start", size=14, mono=False):
    """A text pill; returns (svg, width)."""
    col = color or t["muted_fg"]
    w = g.text_width(label, size, mono=mono, weight=500) + 18
    x0 = {"start": x, "end": x - w}.get(anchor, x - w / 2)
    svg = (f'<rect x="{x0:.1f}" y="{y}" width="{w:.1f}" height="24" rx="12" fill="{t["bg"]}" '
           f'stroke="{mix(col, t["bg"], 0.5)}"/>'
           + text(x0 + w / 2, y + 16.5, label, t, size=size, weight=500, fill=col, anchor="middle",
                  mono=mono))
    return svg, w


def loop_arc(cx, y, w, t, label, tone=None):
    """A self-loop drawn above a node: leaves the top right, comes back to the top left."""
    col = t["danger"] if tone == "danger" else t["outline"]
    mk = "danger" if tone == "danger" else "outline"
    x1, x2 = cx + w * 0.30, cx - w * 0.30
    d = f"M{x1} {y} C{x1 + 30} {y - 46} {x2 - 30} {y - 46} {x2} {y}"
    return (f'<path d="{d}" fill="none" stroke="{col}" stroke-width="1.5" stroke-linecap="round" '
            f'stroke-dasharray="5 5" class="march" marker-end="url(#arrow-{mk})"/>'
            + (text(cx, y - 44, label, t, size=14, weight=500, fill=t["muted_fg"],
                    anchor="middle") if label else ""))


# ------------------------------------------------------------------------- Learn: tutorials

def learn_ask_for_approval():
    p = grid(24, 24, 152, 70, 72, 60)
    d = Scene("learn-ask-for-approval",
              "The refund workflow: a refund of 50 or less is approved by a rule, a larger one "
              "stops at the ask step until a person answers, and the verdict branch sends the "
              "answer to approved or declined.", w=872, h=378)
    d.node("req", *p(0, 0), 152, 70, "trigger", "request", ["API_TRIGGER"], "bolt", step=0)
    d.node("size", *p(1, 0), 152, 70, "processor", "size", ["BRANCH"], "branch", step=1)
    d.node("auto", *p(2, 0), 152, 70, "processor", "auto", ["by rule"], "gate")
    d.node("ask", *p(1, 1), 152, 70, "processor", "ask", ["waits for", "a person"], "users",
           live=True)
    d.node("ver", *p(2, 1), 152, 70, "processor", "verdict", ["BRANCH"], "branch")
    d.node("yes", *p(3, 1), 152, 70, "terminal", "approved", ["approve"], "check")
    d.node("no", *p(3, 2), 152, 70, "terminal", "declined", ["otherwise"], "flag")
    d.edge("req", "size")
    d.edge("size", "auto", label="small")
    d.edge("size", "ask", sa="b", sb="t", label="big")
    d.edge("ask", "ver")
    d.edge("ver", "yes", label="yes")
    d.edge("ver", "no", sa="b", sb="l", label="no")
    return d


def learn_mix_rules_and_ai():
    p = grid(24, 28, 176, 66, 80, 50)
    d = Scene("learn-mix-rules-and-ai",
              "The ticket workflow: rule steps decide what is urgent, only the draft step asks a "
              "model, and if the model fails a plain rule sends a canned reply.", w=736, h=350)

    def under(t):
        x, y = p(1, 1)
        return (f'<rect x="{x - 12}" y="{y - 12}" width="{176 + 24}" height="{66 + 24}" rx="16" '
                f'fill="{mix(t["trigger"], t["bg"], 0.07)}" stroke="{t["trigger"]}" '
                f'stroke-opacity=".5" stroke-dasharray="4 5"/>')

    def over(t):
        x, y = p(1, 1)
        s, w = pill(x - 20, y + 21, "the only model", t, color=t["trigger"], anchor="end")
        return s

    d.under, d.over = under, over
    d.node("tk", *p(0, 0), 176, 66, "trigger", "ticket", ["API_TRIGGER"], "bolt", step=0)
    d.node("route", *p(1, 0), 176, 66, "processor", "route", ["rule: BRANCH"], "branch", step=1)
    d.node("page", *p(2, 0), 176, 66, "processor", "page", ["rule: MAPPER"], "gate")
    d.node("draft", *p(1, 1), 176, 66, "processor", "draft", ["LLM_CALL"], "spark", live=True)
    d.node("rm", *p(2, 1), 176, 66, "processor", "reply-model", ["model's text"], "shuffle")
    d.node("rc", *p(1, 2), 176, 66, "processor", "reply-canned", ["rule: template"], "shield")
    d.edge("tk", "route")
    d.edge("route", "page", label="urgent")
    d.edge("route", "draft", sa="b", sb="t", label="normal")
    d.edge("draft", "rm", label="success")
    d.edge("draft", "rc", sa="b", sb="t", label="failure", tone="danger")
    return d


def learn_add_a_supervisor():
    d = Scene("learn-add-a-supervisor",
              "A stop command from a person or an assistant passes the supervisor no-kill, which "
              "owns the session nightly. KILL is refused with a 403 and your reason; DRAIN or "
              "CANCEL stops the job.", w=760, h=394)
    p = grid(24, 30, 170, 70, 100, 62)
    d.node("who", *p(0, 1), 170, 70, "terminal", "caller", ["person or AI"], "users")
    d.node("sup", *p(1, 1), 170, 70, "terminal", "no-kill", ["owns nightly"], "eye", live=True)
    d.node("deny", *p(2, 0), 170, 70, "terminal", "403", ["and the reason"], "shield")
    d.node("job", *p(2, 2), 170, 70, "processor", "job", ["stops"], "play")
    d.edge("who", "sup", kind="ctl", label="stop")
    d.edge("sup", "deny", kind="ctl", label="KILL", tone="danger")
    d.edge("sup", "job", kind="ctl", label="DRAIN, CANCEL", label_dx=0)
    return d


def sequence(name, alt, actors, rows, w, h, gate=None, top=22):
    """A message-sequence picture. actors: [(id, title, sub, role, icon)], rows: [(from, to, label,
    dashed)] indexes. gate: (first_row, last_row, label)."""
    n = len(actors)
    cw = 190
    xs = [w * (i + 0.5) / n for i in range(n)]
    rowy = lambda i: top + 98 + i * 36

    def under(t):
        out = []
        for x in xs:
            out.append(f'<path d="M{x} {top + 74} V{h - 16}" stroke="{t["border"]}" '
                       'stroke-width="2" stroke-dasharray="3 6"/>')
        if gate:
            a, b, label = gate
            y0, y1 = rowy(a) - 22, rowy(b) + 14
            out.append(f'<rect x="{xs[0] - 30}" y="{y0}" width="{xs[-1] - xs[0] + 60}" '
                       f'height="{y1 - y0}" rx="12" fill="{mix(t["trigger"], t["bg"], 0.07)}" '
                       f'stroke="{t["trigger"]}" stroke-opacity=".5" stroke-dasharray="4 5"/>')
            s, _ = pill(xs[-1] + 18, (y0 + y1) / 2 - 12, label, t, color=t["trigger"], anchor="end")
            out.append(s)
        return "".join(out)

    def over(t):
        out = []
        for i, (a, b, label, dash) in enumerate(rows):
            y = rowy(i)
            x1, x2 = xs[a], xs[b]
            s = 1 if x2 > x1 else -1
            out.append(arrow(x1 + 4 * s, y, x2 - 4 * s, y, t, dash=dash))
            mono = "_" in label
            out.append(text((x1 + x2) / 2, y - 7, label, t, size=14, weight=500,
                            fill=t["fg"], anchor="middle", mono=mono))
        return "".join(out)

    d = Scene(name, alt, under, over, w=w, h=h)
    for (aid, title, sub, role, ic), x in zip(actors, xs):
        d.node(aid, x - cw / 2, top, cw, 64, role, title, [sub] if sub else [], ic)
    return d


def learn_let_an_assistant():
    actors = [("you", "You", "describe, approve", "terminal", "users"),
              ("as", "Assistant", "Claude Code", "processor", "spark"),
              ("yk", "Yukta /mcp", "the MCP server", "trigger", "server")]
    rows = [(0, 1, "describe the gate", False),
            (1, 2, "whoami, list_plugins", True),
            (1, 0, "show the JSON", False),
            (0, 1, "yes, install it", False),
            (1, 2, "create_session", True),
            (1, 2, "start_workflow", True),
            (1, 2, "get_execution_result", True),
            (1, 0, "verdict and why", False)]
    return sequence("learn-let-an-assistant-build-a-workflow",
                    "You describe the gate, the assistant looks up what Yukta offers and shows the "
                    "workflow JSON, you approve it, and only then does the assistant install and "
                    "run it and read the result back from Yukta.",
                    actors, rows, 700, 440, gate=(2, 3, "you approve before it is installed"))


def learn_set_up_supervision():
    steps = [("Ask for", "a plan"), ("Dry-run save", "and assign"),
             ("Run, read", "the decisions"), ("Replay on", "a past run"),
             ("Switch dry", "run off")]
    cw, ch, gap, x0 = 150, 76, 34, 22
    W, H = x0 * 2 + 5 * cw + 4 * gap, 400

    def top(i):
        return 250 - i * 48

    def under(t):
        out = []
        # Bands under the steps: what each stretch does to live runs.
        bands = [(0, 0, "nothing saved", t["terminal"]), (1, 3, "dry run: nothing is sent", t["processor"]),
                 (4, 4, "live", t["trigger"])]
        for a, b, label, col in bands:
            x0_ = x0 + a * (cw + gap)
            w_ = (b - a) * (cw + gap) + cw
            out.append(f'<rect x="{x0_}" y="{H - 60}" width="{w_}" height="36" rx="10" '
                       f'fill="{mix(col, t["bg"], 0.10)}" stroke="{mix(col, t["bg"], 0.55)}" '
                       'stroke-dasharray="4 5"/>')
            out.append(text(x0_ + w_ / 2, H - 37, label, t, size=15, weight=600, fill=col,
                            anchor="middle"))
        return "".join(out)

    def over(t):
        out = []
        for i, (a, b) in enumerate(steps):
            x, y = x0 + i * (cw + gap), top(i)
            col = t["trigger"] if i == 4 else t["processor"]
            out.append(panel(x, y, cw, ch, t, r=12, stroke=mix(col, t["card"], 0.5)))
            out.append(f'<circle cx="{x + 22}" cy="{y + ch / 2}" r="12" fill="{col}"/>')
            out.append(text(x + 22, y + ch / 2 + 5.5, str(i + 1), t, size=15, weight=700,
                            fill="#FFFFFF", anchor="middle"))
            out.append(text(x + 42, y + ch / 2 - 3, a, t, size=15, weight=600))
            out.append(text(x + 42, y + ch / 2 + 15, b, t, size=15, weight=600))
            if i < 4:
                out.append(arrow(x + cw + 3, y + ch / 2, x + cw + gap - 4, top(i + 1) + ch / 2, t))
        return "".join(out)

    return Scene("learn-set-up-supervision-with-an-assistant",
                 "Five steps, each higher than the last: ask for a plan, save the supervisor in "
                 "dry run and assign it, run and read the decisions, replay on a past run, then "
                 "switch dry run off. Only the last step touches live runs.", under, over, w=W, h=H)


# ------------------------------------------------------------------------- Guides: build

def guide_route_and_branch():
    p = grid(24, 28, 180, 70, 116, 32)
    d = Scene("guide-route-and-branch",
              "A BRANCH node looks up the value in its cases; each case names an output port, an "
              "edge with that sourcePort carries the message on, and defaultPort catches the rest.",
              w=820, h=346)
    d.node("tk", *p(0, 1), 180, 70, "trigger", "ticket", ["API_TRIGGER"], "bolt", step=0)
    d.node("br", *p(1, 1), 180, 70, "processor", "by-kind", ["BRANCH", "payload.kind"], "branch",
           step=1)
    d.node("bug", *p(2, 0), 180, 70, "terminal", "bug-queue", ["kind is bug"], "inbox")
    d.node("feat", *p(2, 1), 180, 70, "terminal", "feature-queue", ["kind is feature"], "inbox")
    d.node("oth", *p(2, 2), 180, 70, "terminal", "other-queue", ["anything else"], "inbox")
    d.edge("tk", "br")
    d.edge("br", "bug", label="bugs")
    d.edge("br", "feat", label="features")
    d.edge("br", "oth", label="other")
    return d


def guide_loop_and_retry():
    W, H = 660, 372

    def under(t):
        out = []
        for y, label, col in ((18, "A step failed: try the same message again", t["danger"]),
                              (194, "Repeat an action until a condition is true", t["processor"])):
            out.append(panel(18, y, W - 36, 160, t, r=14))
            out.append(text(W - 36, y + 30, label, t, size=15, weight=600, fill=col,
                            anchor="end"))
        return "".join(out)

    def over(t):
        out = [loop_arc(158, 100, 170, t, ""), loop_arc(158, 276, 170, t, "")]
        s, _ = pill(158 - 46, 100 - 46, "maxAttempts", t, color=t["muted_fg"])
        out.append(s)
        s, _ = pill(158 - 90, 276 - 46, "maxIterations, maxDuration", t, color=t["muted_fg"])
        out.append(s)
        return "".join(out)

    d = Scene("guide-loop-and-retry",
              "Two tools for two needs. engine.retry runs one failed message through the same "
              "node again and, when every attempt fails, sends it out of the error port. "
              "LOOP_PREDICATE runs a target again and again until its exit condition is true or "
              "a bound stops it.", under, over, w=W, h=H)
    d.node("call", 74, 100, 168, 62, "processor", "call-api", ["engine.retry"], "repeat", step=0)
    d.node("dead", 420, 100, 200, 62, "terminal", "dead-letter", ["after the last try"], "inbox")
    d.node("loop", 74, 276, 168, 62, "processor", "until-three", ["LOOP_PREDICATE"], "repeat",
           step=1)
    d.node("done", 420, 276, 200, 62, "terminal", "done", ["exit condition true"], "flag")
    d.edge("call", "dead", label="error", tone="danger")
    d.edge("loop", "done", label="exit")
    return d


def guide_handle_failures():
    p = grid(24, 40, 170, 72, 90, 34)
    d = Scene("guide-handle-failures",
              "A node with onError ROUTE retries a failing message, then sends it out of its error "
              "port to a dead-letter terminal, so the run carries on and still ends SUCCESS.",
              w=738, h=324)

    def over(t):
        x, y = p(1, 1)
        return loop_arc(x + 78, y, 170, t, "2 attempts", tone="danger")

    d.over = over
    d.node("tk", *p(0, 1), 170, 72, "trigger", "ticket", ["API_TRIGGER"], "bolt", step=0)
    d.node("by", *p(1, 1), 170, 72, "processor", "by-kind", ["BRANCH", "onError ROUTE"], "branch",
           step=1)
    d.node("bug", *p(2, 0), 170, 72, "terminal", "bug-queue", ["kind is bug"], "inbox")
    d.node("dl", *p(2, 1), 170, 72, "terminal", "dead-letter", ["unknown kind"], "inbox")
    d.edge("tk", "by")
    d.edge("by", "bug", label="bugs")
    d.edge("by", "dl", label="error", tone="danger")
    return d


def guide_control_a_run():
    W, H = 664, 372
    d = Scene("guide-control-a-run",
              "Control commands reach a live run in order: pause and resume hold it with every "
              "time limit frozen, and a stop has three modes. DRAIN lets in-flight work finish, "
              "CANCEL cancels it and KILL ends the run at once.", w=W, h=H)
    d.node("run", 24, 148, 210, 76, "processor", "a run", ["commands in order"], "play",
           live=True)
    rows = [("hold", "pause / resume", ["time limits freeze"], "clock"),
            ("drain", "stop DRAIN", ["in-flight work finishes"], "shield"),
            ("cancel", "stop CANCEL", ["in-flight work cancelled"], "flag"),
            ("kill", "stop KILL", ["ends the run at once"], "bolt")]
    for i, (nid, title, sub, ic) in enumerate(rows):
        d.node(nid, 360, 24 + i * 86, 280, 68, "terminal", title, sub, ic, mono=True)
        d.edge("run", nid, kind="ctl", fb=0.5)
    return d


def guide_validate_and_roll_back():
    W, H = 790, 346

    def under(t):
        return (panel(18, 150, W - 36, 180, t, r=14)
                + text(W - 36, 178, "versions of one workflow, newest 20 kept", t, size=15,
                       weight=600, fill=t["muted_fg"], anchor="end"))

    def over(t):
        # v3 is the bad save, v4 is the rollback: a new version that copies v1.
        out = [f'<rect x="{418 - 3}" y="{208 - 3}" width="{140 + 6}" height="{70 + 6}" rx="14" '
               f'fill="none" stroke="{t["danger"]}" stroke-width="1.5"/>']
        s, _ = pill(438, 188, "broken", t, color=t["danger"])
        out.append(s)
        out.append(f'<path d="M{598 + 70} {278 + 4} C{598 + 70} 322 {58 + 70} 322 {58 + 70} 282" '
                   f'fill="none" stroke="{t["outline"]}" stroke-width="1.5" '
                   f'stroke-dasharray="5 5" class="march" marker-end="url(#arrow-outline)"/>')
        s, w = pill(398, 296, "rollback saves v1 again as a new version", t, anchor="mid")
        out.append(s)
        return "".join(out)

    d = Scene("guide-validate-and-roll-back",
              "A draft is validated before it is saved; every save adds a version. When v3 breaks "
              "the workflow, rollback saves the old v1 definition again as the newest version, so "
              "history stays in a line.", under, over, w=W, h=H)
    d.node("dr", 40, 30, 170, 70, "trigger", "draft", ["your JSON"], "code")
    d.node("val", 290, 30, 190, 70, "processor", "validate", ["stores nothing"], "shield", step=0)
    d.edge("dr", "val")
    for i, (nid, title, sub) in enumerate((("v1", "v1", "known good"), ("v2", "v2", "a change"),
                                           ("v3", "v3", "a change"), ("v4", "v4", "same as v1"))):
        d.node(nid, 58 + i * 180 + (i > 1) * 0, 208, 140, 70, "terminal", title, [sub], "layers")
    for a, b in (("v1", "v2"), ("v2", "v3"), ("v3", "v4")):
        d.edge(a, b)
    d.edge("val", "v2", sa="b", sb="t", kind="ctl", label="save adds one", label_t=0.45,
           label_dx=40)
    return d


def guide_run_a_sub_workflow():
    W, H = 790, 380

    def under(t):
        return (panel(18, 232, W - 36, 130, t, r=14)
                + text(36, 258, "workflow check, the child", t, size=15, weight=600,
                       fill=t["muted_fg"]))

    d = Scene("guide-run-a-sub-workflow",
              "The SUB_WORKFLOW node run-check starts the child workflow check and reads the "
              "outcome of the terminal the child reached. With routeByOutcome the parent leaves on "
              "success or failure.", under, w=W, h=H)
    d.node("st", 24, 40, 150, 66, "trigger", "start", ["API_TRIGGER"], "bolt", step=0)
    d.node("rc", 244, 40, 190, 66, "processor", "run-check", ["SUB_WORKFLOW", "routeByOutcome"],
           "child", step=1)
    d.node("sh", 560, 12, 200, 66, "terminal", "ship", ["child said success"], "flag")
    d.node("sp", 560, 112, 200, 66, "terminal", "stop-here", ["child said failure"], "flag")
    d.node("in", 40, 278, 150, 66, "trigger", "in", ["API_TRIGGER"], "bolt")
    d.node("vd", 266, 278, 150, 66, "processor", "verdict", ["MAPPER"], "shuffle")
    d.node("dn", 540, 278, 210, 66, "terminal", "done", ["OUTCOME_TERMINAL"], "check")
    d.edge("st", "rc")
    d.edge("rc", "sh", label="success")
    d.edge("rc", "sp", label="failure", tone="danger")
    d.edge("rc", "in", sa="b", sb="t", kind="ctl", label="starts", bow=44)
    d.edge("in", "vd")
    d.edge("vd", "dn")
    return d


def guide_schedule_a_workflow():
    W, H = 700, 340

    def under(t):
        out = []
        for y, label in ((18, "Yukta keeps the time: one run stays open"),
                         (182, "Your scheduler keeps the time: one short run per tick")):
            out.append(panel(18, y, W - 36, 144, t, r=14))
            out.append(text(36, y + 28, label, t, size=15, weight=600, fill=t["muted_fg"]))
        return "".join(out)

    def over(t):
        out = []
        x = 272  # ticks travelling into the one open run, between the two nodes
        for i in (1, 2, 3):
            s, w = pill(x, 112, f"tick {i}", t, color=t["trigger"], size=13)
            out.append(s)
            x += w + 8
        x = 512  # three short runs
        for i in (1, 2, 3):
            s, w = pill(x, 244, f"run {i}", t, color=t["processor"], size=13)
            out.append(s)
            x += w + 6
        out.append(arrow(494, 256, 509, 256, t))
        return "".join(out)

    d = Scene("guide-schedule-a-workflow",
              "Two ways to repeat a job. SCHEDULE_TRIGGER ticks inside one run that stays open; an "
              "external scheduler starts a short, separate run for every tick.", under, over, w=W,
              h=H)
    d.node("st", 44, 62, 214, 60, "trigger", "SCHEDULE_TRIGGER", ["ticks inside the run"], "clock",
           mono=True, step=0)
    d.node("one", 500, 62, 170, 60, "processor", "one open run", ["holds one slot"], "repeat",
           step=1)
    d.node("ext", 44, 226, 214, 60, "terminal", "your scheduler", ["cron, CI, cloud"], "clock")
    d.node("sh", 340, 226, 150, 60, "processor", "start", ["API_TRIGGER"], "play")
    d.edge("st", "one")
    d.edge("ext", "sh", kind="ctl")
    return d


# ------------------------------------------------------------------------- Guides: AI

def guide_call_an_llm():
    p = grid(24, 28, 176, 70, 100, 40)
    d = Scene("guide-call-an-llm",
              "An LLM_CALL with failureMode CONTINUE and routeByStatus leaves on success or "
              "failure; the failure port can lead to a second LLM_CALL with another model.",
              w=776, h=338)
    d.node("in", *p(0, 1), 176, 70, "trigger", "in", ["API_TRIGGER"], "bolt", step=0)
    d.node("m", *p(1, 1), 176, 70, "processor", "model", ["LLM_CALL"], "spark", step=1)
    d.node("out", *p(2, 0), 176, 70, "terminal", "out", ["payload.content"], "flag")
    d.node("fb", *p(2, 2), 176, 70, "processor", "fallback", ["another model"], "spark")
    d.edge("in", "m")
    d.edge("m", "out", label="success")
    d.edge("m", "fb", label="failure", tone="danger")
    return d


def guide_use_tools():
    p = grid(24, 24, 190, 66, 84, 46)
    d = Scene("guide-use-tools-and-mcp-servers",
              "The model only suggests a tool call. The route node branches on whether it asked, "
              "the act node runs the tool from an allow-list, and a risky tool can wait for a "
              "person first.", w=786, h=338)
    d.node("tools", *p(0, 0), 190, 66, "processor", "tools", ["MCP_LIST_TOOLS"], "list", step=0)
    d.node("plan", *p(1, 0), 190, 66, "processor", "plan", ["LLM_CALL"], "spark", step=1)
    d.node("route", *p(1, 1), 190, 66, "processor", "route", ["BRANCH"], "branch", step=2)
    d.node("ans", *p(0, 1), 190, 66, "terminal", "direct-answer", ["no tool needed"], "flag")
    d.node("act", *p(2, 1), 190, 66, "processor", "act", ["MCP_TOOL_CALL", "allowedTools"],
           "wrench", step=3)
    d.node("per", *p(2, 0), 190, 66, "terminal", "a person", ["requireApproval"], "users")
    d.node("fin", *p(2, 2), 190, 66, "terminal", "final-answer", ["LLM_CALL"], "flag")
    d.edge("tools", "plan")
    d.edge("plan", "route", sa="b", sb="t")
    d.edge("route", "ans", sa="l", sb="r", label="answer")
    d.edge("route", "act", label="tool")
    d.edge("per", "act", sa="b", sb="t", kind="ctl", label="approves")
    d.edge("act", "fin", sa="b", sb="t")
    return d


def guide_set_token_budgets():
    W, H = 760, 372
    d = Scene("guide-set-token-budgets",
              "Every model and tool node that shares an agent name draws on one budget. Without "
              "hardStop the next call fails like any failed call; with hardStop the whole run "
              "ends.", w=W, h=H)
    d.node("a", 24, 28, 188, 70, "processor", "plan", ["LLM_CALL", "maxTokens"], "spark")
    d.node("b", 24, 150, 188, 70, "processor", "act", ["MCP_TOOL_CALL", "maxCalls"], "wrench")
    d.node("c", 24, 272, 188, 70, "processor", "final-answer", ["LLM_CALL", "maxTokens"], "spark")
    d.node("led", 296, 150, 188, 70, "terminal", "budget", ["one shared ledger"], "db", live=True)
    d.node("soft", 556, 40, 180, 70, "processor", "node fails", ["error routing", "still applies"],
           "shuffle")
    d.node("hard", 546, 260, 200, 70, "terminal", "run ends", ["no retry, no route"], "flag")
    for n in "abc":
        d.edge(n, "led", kind="ctl")
    d.edge("led", "soft", label="used up")
    d.edge("led", "hard", label="hardStop", tone="danger")
    return d


def guide_classify_gate_and_score():
    ROWS = (("CLASSIFY", "which label?", (("your labels", "ok"), ("uncertain", "mute"))),
            ("GATE", "yes or no?", (("pass", "ok"), ("reject", "bad"))),
            ("SCORE", "how high?", (("high", "ok"), ("low", "mute"))),
            ("ANOMALY", "unusual?", (("normal", "ok"), ("anomalous", "bad"))),
            ("DEDUPE", "seen before?", (("unique", "ok"), ("duplicate", "mute"))))
    W, H = 690, 330

    def under(t):
        out = [panel(18, 16, W - 36, H - 32, t)]
        out.append(text(60, 48, "decision plugin", t, size=14, weight=500, fill=t["muted_fg"]))
        out.append(text(215, 48, "question it answers", t, size=14, weight=500, fill=t["muted_fg"]))
        out.append(text(400, 48, "output ports", t, size=14, weight=500, fill=t["muted_fg"]))
        return "".join(out)

    def over(t):
        out = []
        tone = {"ok": t["processor"], "bad": t["danger"], "mute": t["terminal"]}
        for i, (name, q, ports) in enumerate(ROWS):
            y = 62 + i * 49
            if i:
                out.append(f'<path d="M32 {y - 4}H{W - 32}" stroke="{t["border"]}" '
                           'stroke-opacity=".7"/>')
            out.append(icon("puzzle", 34, y + 12, t["processor"], scale=1.0))
            out.append(text(60, y + 28, name, t, size=16, weight=700, mono=True))
            out.append(text(215, y + 28, q, t, size=16, weight=500))
            x = 400
            for label, kind in ports:
                c, w = chip(x, y + 12, label, t, tone[kind])
                out.append(c)
                x += w + 8
        return "".join(out)

    return Scene("guide-classify-gate-and-score",
                 "Pick the decision plugin by the question: CLASSIFY picks a label, GATE answers "
                 "yes or no, SCORE rates on a scale, ANOMALY spots the unusual and DEDUPE spots "
                 "repeats. Each leaves on its own output ports.", under, over, w=W, h=H)


def guide_configure_with_assistant():
    p = grid(24, 78, 188, 84, 84, 66)
    W, H = 560, 340
    d = Scene("guide-configure-with-an-assistant",
              "Every change made through an assistant follows one habit: ask for the result and a "
              "preview, review what it shows, apply only after you say yes, then check with a "
              "different client.", w=W, h=H)

    def over(t):
        x1, y = p(0, 0)
        x2, _ = p(1, 0)
        # "not right? say what to change": an arc from Review back over to Ask.
        arc = (f'<path d="M{x2 + 94} {y - 4} C{x2 + 94} {y - 50} {x1 + 94} {y - 50} {x1 + 94} '
               f'{y - 4}" fill="none" stroke="{t["outline"]}" stroke-width="1.5" '
               'stroke-dasharray="5 5" class="march" marker-end="url(#arrow-outline)"/>')
        s, _ = pill((x1 + x2) / 2 + 94, y - 54, "not right? say what to change", t,
                    anchor="mid")
        return arc + s

    d.over = over
    d.node("ask", *p(0, 0), 188, 84, "trigger", "1 Ask", ["name the result", "show me first"],
           "send", step=0)
    d.node("rev", *p(1, 0), 188, 84, "processor", "2 Review", ["read the JSON", "or the plan"],
           "eye", step=1, live=True)
    d.node("app", *p(1, 1), 188, 84, "processor", "3 Apply", ["you say yes"], "check", step=2)
    d.node("chk", *p(0, 1), 188, 84, "terminal", "4 Check", ["another client"], "terminal")
    d.edge("ask", "rev")
    d.edge("rev", "app", sa="b", sb="t")
    d.edge("app", "chk", sa="l", sb="r")
    return d


# ------------------------------------------------------------------------- Guides: supervise

def guide_assign_a_supervisor():
    W, H = 790, 372

    def under(t):
        out = []
        layers = [(18, 16, 540, 340, "server", "server-wide commands", t["terminal"]),
                  (36, 62, 504, 276, "session:<id>", "every workflow of it", t["terminal"]),
                  (54, 108, 468, 212, "workflow:<s>/<w>", "run level and every node",
                   t["processor"])]
        for x, y, w, h, name, sub, col in layers:
            out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" '
                       f'fill="{mix(col, t["bg"], 0.07)}" stroke="{mix(col, t["bg"], 0.5)}"/>')
            out.append(text(x + 16, y + 28, name, t, size=15, weight=700, mono=True))
            lw = g.text_width(name, 15, mono=True, weight=700)
            out.append(text(x + 28 + lw, y + 28, sub, t, size=14, weight=400, fill=t["muted_fg"]))
        for x, name, sub in ((72, "run:<s>/<w>", "the run level only"),
                             (300, "//selector", "some nodes: id, prefix.*, group:")):
            out.append(panel(x, 158, 210, 138, t, r=12))
            out.append(text(x + 14, 186, name, t, size=15, weight=700, mono=True))
            out.append(text(x + 14, 212, sub if x == 72 else "some nodes:", t, size=14,
                            fill=t["muted_fg"]))
            if x == 72:
                out.append(text(x + 14, 234, "start, pause,", t, size=14, fill=t["muted_fg"]))
                out.append(text(x + 14, 254, "stop, restart", t, size=14, fill=t["muted_fg"]))
            else:
                for j, s in enumerate(("a node id,", "<prefix>.*,", "group:<stage>")):
                    out.append(text(x + 14, 234 + j * 20, s, t, size=14, fill=t["muted_fg"],
                                    mono=True))
        return "".join(out)

    def over(t):
        out = [arrow(566, 186, 524, 186, t, dash=True)]
        s, _ = pill(570, 262, "one owner per unit", t, color=t["primary"])
        out.append(s)
        s, _ = pill(570, 292, "else 409 conflict", t, color=t["danger"])
        out.append(s)
        return "".join(out)

    d = Scene("guide-assign-a-supervisor",
              "An assignment names the unit a supervisor owns: the whole server, a session, a "
              "workflow, only its run level, or chosen nodes. Every unit has exactly one owner.",
              under, over, w=W, h=H)
    d.node("sup", 570, 150, 190, 72, "terminal", "release-guard", ["supervisor"], "eye", live=True)
    # drawn node sits above the arrow tail; keep the arrow clear of it
    return d


def guide_build_a_hierarchy():
    W, H = 810, 372

    def under(t):
        out = [f'<rect x="276" y="22" width="232" height="228" rx="16" '
               f'fill="{mix(t["terminal"], t["bg"], 0.07)}" stroke="{t["outline"]}" '
               'stroke-opacity=".55" stroke-dasharray="5 5" class="march"/>']
        out.append(text(292, 48, "parents, in order", t, size=14, weight=500, fill=t["muted_fg"]))
        return "".join(out)

    def over(t):
        out = []
        modes = (("ORDERED", "asked one by one; a silent parent passes it on", t["processor"]),
                 ("ALL", "asked at once; all must allow, one refusal is final", t["processor"]),
                 ("ANY", "asked at once; the first answer decides", t["processor"]))
        y = 276
        for i, (m, s, col) in enumerate(modes):
            c, w = chip(24, y + i * 30, m, t, col)
            out.append(c)
            out.append(text(24 + w + 10, y + i * 30 + 16, s, t, size=14, weight=400,
                            fill=t["muted_fg"]))
        return "".join(out)

    d = Scene("guide-build-a-hierarchy",
              "The owner release-guard parks a command that needs approval and asks its parents "
              "first, under a delegation mode: ORDERED, ALL or ANY. A refusal ends the command; a "
              "parent with no opinion leaves it to a person.", under, over, w=W, h=H)
    d.node("own", 24, 112, 190, 76, "terminal", "release-guard", ["owns the workflow"], "eye")
    d.node("p1", 300, 62, 184, 66, "terminal", "policy", ["rules, global"], "shield")
    d.node("p2", 300, 160, 184, 66, "terminal", "agent", ["AI, global"], "spark")
    d.node("per", 610, 112, 170, 76, "trigger", "a person", ["decides last"], "users", live=True)
    d.edge("own", "p1", kind="ctl", label="asks")
    d.edge("own", "p2", kind="ctl")
    d.edge("p1", "per", kind="ctl", label="no opinion", fb=0.3, label_dx=14)
    d.edge("p2", "per", kind="ctl", label="silent", fb=0.7, label_dx=14)
    return d


def guide_replay_a_supervisor():
    W, H = 790, 340
    d = Scene("guide-replay-a-supervisor",
              "A replay feeds the stored status events of a finished run to the supervisor in dry "
              "run and lists the commands it would have sent, each marked allowed or not. Nothing "
              "is sent to any run.", w=W, h=H)
    d.node("run", 24, 40, 190, 70, "terminal", "finished run", ["status events"], "db")
    d.node("dr", 24, 210, 190, 70, "trigger", "draft rules", ["optional"], "code")
    d.node("sup", 296, 125, 200, 76, "processor", "ci-guard", ["supervisor, dry run"], "eye",
           step=0)
    d.node("dec", 566, 40, 200, 76, "terminal", "decisions", ["allowed: yes or no"], "list")
    d.node("live", 566, 224, 200, 70, "terminal", "the live run", ["unchanged"], "play")
    d.edge("run", "sup", sa="r", sb="t", fa=0.5, fb=0.5, label="replay")
    d.edge("dr", "sup", sa="r", sb="b", label="draft")
    d.edge("sup", "dec", sa="r", sb="l")
    d.edge("sup", "live", sa="r", sb="l", kind="ctl", label="nothing sent")
    return d


def guide_route_input_requests():
    W, H = 840, 350
    d = Scene("guide-route-input-requests",
              "A route puts tiers in a row. An input request goes first to the auto tier of "
              "supervisors, then, on a timeout or a hand-up, to people with the OPERATOR role; if "
              "the last tier times out, on-final-timeout FAIL fails the asking node.", w=W, h=H)
    d.node("rq", 24, 60, 160, 72, "trigger", "request", ["input request"], "inbox", step=0)
    d.node("auto", 244, 60, 170, 72, "processor", "auto", ["supervisor:*", "30 s, AUTO"], "eye",
           step=1)
    d.node("ops", 484, 60, 170, 72, "terminal", "ops", ["role:OPERATOR", "1 h, HUMAN"], "users",
           step=2)
    d.node("fail", 540, 244, 190, 72, "terminal", "node fails", ["on-final-timeout"], "flag")
    d.node("ok", 244, 232, 170, 72, "processor", "answered", ["request closed"], "check")
    d.edge("rq", "auto")
    d.edge("auto", "ops", label="hand up", label_dy=-4)
    d.edge("auto", "ok", sa="b", sb="t", label="answer")
    d.edge("ops", "ok", sa="b", sb="r", fa=0.2, label="answer", label_t=0.3)
    d.edge("ops", "fail", sa="r", sb="r", label="timeout", tone="danger", bow=60)
    return d


def main():
    for fn in (learn_ask_for_approval, learn_mix_rules_and_ai, learn_add_a_supervisor,
               learn_let_an_assistant, learn_set_up_supervision, guide_route_and_branch,
               guide_loop_and_retry, guide_handle_failures, guide_control_a_run,
               guide_validate_and_roll_back, guide_run_a_sub_workflow, guide_schedule_a_workflow,
               guide_call_an_llm, guide_use_tools, guide_set_token_budgets,
               guide_classify_gate_and_score, guide_configure_with_assistant,
               guide_assign_a_supervisor, guide_build_a_hierarchy, guide_replay_a_supervisor,
               guide_route_input_requests):
        fn().save()


if __name__ == "__main__":
    main()
