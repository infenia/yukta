# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Diagrams of the Operate and Extend guides (`ops-*`) and of the Reference section (`ref-*`).

Run through generate.py (it calls main()), or alone:  python3 docs/images/diagrams/gen_reference.py

Every picture is drawn from facts on the page that embeds it (property names, roles, reject reasons,
build switches); nothing here is decoration. Same kit as scenes.py: one idea per diagram, labels of a
few words, colour = meaning (orange starts or is live, blue is the engine at work, slate stores or
ends or is an outside actor, red is an error path), dashed = commands and events.
"""

from generate import mix, text_width
from scenes import Scene, chip, icon, panel, text


def _ring(x, y, w, h, color, t, r=18):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" '
            f'fill="{mix(color, t["bg"], 0.07)}" stroke="{mix(color, t["bg"], 0.55)}" '
            'stroke-width="1.5"/>')


def _arrow(d, t, dashed=False, color=None, marker="outline"):
    dash = ' stroke-dasharray="5 5" class="march"' if dashed else ""
    return (f'<path d="{d}" fill="none" stroke="{color or t["outline"]}" stroke-width="1.5" '
            f'stroke-linecap="round"{dash} marker-end="url(#arrow-{marker})"/>')


def _chips(t, x, y, labels, color, maxw, gap=8):
    """Flow chips left to right, wrapping at maxw. Returns (svg, bottom y)."""
    out, cx = [], x
    for lab in labels:
        w = text_width(lab, 14, mono=True) + 18
        if cx + w > x + maxw:
            cx, y = x, y + 30
        c, w = chip(cx, y, lab, t, color)
        out.append(c)
        cx += w + gap
    return "".join(out), y + 22


# ===================================================================== operate guides


def ops_deploy():
    def under(t):
        out = [f'<rect x="322" y="34" width="546" height="372" rx="18" '
               f'fill="{mix(t["terminal"], t["bg"], 0.07)}" stroke="{t["outline"]}" '
               'stroke-opacity=".55" stroke-dasharray="5 5" class="march"/>',
               text(342, 392, "one host", t, size=14, weight=500, fill=t["muted_fg"])]
        return "".join(out)

    d = Scene("ops-deploy",
              "Clients reach Yukta over HTTPS through a reverse proxy on the same host. Yukta "
              "itself listens on loopback port 9858 and checks the API key; the container or "
              "service is probed at /actuator/health.", under, w=880, h=440)
    d.node("c", 30, 90, 190, 76, "trigger", "Clients", ["REST, UI, agents"], "users", step=0)
    d.node("p", 345, 90, 190, 76, "processor", "Reverse proxy", ["ends TLS"], "shield", step=1)
    d.node("y", 660, 90, 190, 76, "processor", "Yukta", ["127.0.0.1:9858"], "server", step=2)
    d.node("h", 345, 270, 190, 76, "terminal", "Health probe", ["/actuator/health"], "pulse")
    d.node("s", 660, 270, 190, 76, "terminal", "State", ["in memory"], "db")
    d.edge("c", "p", label="HTTPS + key")
    d.edge("p", "y", label="HTTP", label_dy=-0)
    d.edge("y", "s", sa="b", sb="t")
    d.edge("h", "y", sa="r", sb="b", fa=0.3, fb=0.2, kind="ctl")
    return d


def ops_persist_sessions():
    COLS = (("Definitions", 440), ("Run history", 575), ("Resume runs", 705), ("Shared", 815))
    ROWS = (("Memory", "the default, any edition", (0, 0, 0, 0)),
            ("Session files", "yukta.bootstrap.dir, any edition", (1, 0, 0, 0)),
            ("Disk", "Plus edition", (1, 1, 1, 0)),
            ("PostgreSQL", "Plus edition", (1, 1, 1, 1)))

    def under(t):
        out = [panel(20, 20, 840, 400, t)]
        for label, cx in COLS:
            out.append(text(cx, 60, label, t, size=15, weight=600, anchor="middle"))
        out.append(f'<path d="M36 78H844" stroke="{t["border"]}"/>')
        for i, (name, sub, marks) in enumerate(ROWS):
            y = 90 + i * 72
            out.append(text(44, y + 32, name, t, size=18, weight=700))
            out.append(text(44, y + 54, sub, t, size=14, weight=400, fill=t["muted_fg"]))
            if i < 3:
                out.append(f'<path d="M36 {y + 72}H844" stroke="{t["border"]}" '
                           'stroke-opacity=".7"/>')
            for (label, cx), m in zip(COLS, marks):
                cy = y + 36
                if m:
                    out.append(icon("check", cx - 10, cy - 10, t["processor"], scale=1.25))
                else:
                    out.append(f'<path d="M{cx - 8} {cy}h16" stroke="{t["muted_fg"]}" '
                               'stroke-width="2" stroke-linecap="round"/>')
        out.append(icon("check", 44, 392, t["processor"], scale=1.0))
        out.append(text(68, 405, "survives a restart; Disk and PostgreSQL need one switch per store",
                        t, size=14, weight=400, fill=t["muted_fg"]))
        return "".join(out)

    return Scene("ops-persist-sessions",
                 "Memory keeps nothing across a restart, session files keep your definitions, "
                 "disk keeps definitions, run history and resume checkpoints, and PostgreSQL "
                 "keeps the same and shares it between servers.", under, w=880, h=440)


def ops_secure_the_server():
    def under(t):
        out = [_ring(20, 24, 480, 392, t["trigger"], t),
               _ring(44, 112, 432, 280, t["processor"], t),
               _ring(68, 200, 384, 168, t["terminal"], t)]
        for y, role, sub, col in ((58, "ADMIN", "+ define workflows, set limits, /actuator", "trigger"),
                                  (146, "OPERATOR", "+ start, stop, steer, answer inputs", "processor"),
                                  (234, "VIEWER", "read sessions, runs and logs", "terminal")):
            x = 44 + (0 if role == "ADMIN" else 24 if role == "OPERATOR" else 48)
            out.append(text(x, y, role, t, size=18, weight=700, fill=t[col], mono=True))
            out.append(text(x, y + 22, sub, t, size=14, weight=400, fill=t["muted_fg"]))
        c, _ = chip(92, 300, "sessions: a list or *", t, t["terminal"])
        out.append(c)
        out.append(text(92, 345, "OPERATOR and VIEWER keys only", t, size=14, weight=400,
                        fill=t["muted_fg"]))
        out.append(text(530, 52, "Workflows are bounded too", t, size=18, weight=700))
        for i, (ic, title, sub) in enumerate((
                ("terminal", "Commands", "yukta.process.allowed-commands"),
                ("globe", "Outbound hosts", "yukta.egress.allowed-hosts"),
                ("shield", "Secrets", "decrypted:name, set on the server"))):
            y = 76 + i * 94
            col = t["processor"]
            out.append(panel(530, y, 330, 76, t))
            out.append(f'<rect x="542" y="{y + 18}" width="40" height="40" rx="10" '
                       f'fill="{mix(col, t["card"], t["tint_mix"])}"/>')
            out.append(icon(ic, 552, y + 28, col, scale=1.25))
            out.append(text(596, y + 34, title, t, size=17, weight=600))
            out.append(text(596, y + 56, sub, t, size=14, weight=400, fill=t["muted_fg"]))
        return "".join(out)

    return Scene("ops-secure-the-server",
                 "Each API key has a role that adds reach: a VIEWER reads, an OPERATOR also starts "
                 "and steers runs, an ADMIN also defines workflows and sets limits. Separately, "
                 "allow-lists bound the commands, outbound hosts and secrets a workflow can use.",
                 under, w=880, h=440)


def ops_observe_runs():
    d = Scene("ops-observe-runs",
              "Every event of a run goes into one ordered queue that status, logs and the live "
              "event feed all read. Metrics and traces leave the server separately, and the "
              "Prometheus and OTLP exports are off until you turn them on.", w=960, h=480)
    d.node("r", 30, 214, 190, 64, "trigger", "A run", ["nodes and events"], "play", step=0)
    d.node("q", 330, 116, 220, 64, "processor", "Event queue", ["one, in order"], "queue", step=1)
    d.node("s", 660, 36, 270, 64, "terminal", "Status", ["workflow status"], "list", step=2)
    d.node("l", 660, 116, 270, 64, "terminal", "Logs and results", ["logs, result"], "eye",
           step=2)
    d.node("f", 660, 196, 270, 64, "terminal", "Live events", ["controlbus watch"], "pulse",
           step=2)
    d.node("m", 660, 308, 270, 64, "terminal", "Metrics", ["/actuator/metrics", "Prometheus: opt in"],
           "sliders")
    d.node("t", 660, 394, 270, 64, "terminal", "Traces", ["OTLP export", "off by default"],
           "graph")
    d.edge("r", "q", fa=0.2, label="events")
    d.edge("q", "s", fa=0.15)
    d.edge("q", "l")
    d.edge("q", "f", fa=0.85)
    d.edge("r", "m", fa=0.8, kind="ctl", label="meters", label_t=0.78)
    d.edge("r", "t", fa=0.95, kind="ctl", label="observations", label_t=0.8)
    return d


def ops_tune_capacity():
    def over(t):
        out = [text(572, 346, "reason tag", t, size=14, weight=500, fill=t["muted_fg"])]
        c, _ = _chips(t, 572, 356, ["limit", "queue_full", "rate_limit"], t["danger"], 330)
        out.append(c)
        return "".join(out)

    d = Scene("ops-tune-capacity",
              "A start takes a free slot and runs. With no slot, on-limit QUEUE parks it as "
              "QUEUED until a slot frees, and on-limit REJECT, a full queue or the rate limit "
              "answer 429 with a reason.", over=over, w=880, h=440)
    d.node("s", 30, 182, 170, 76, "trigger", "Start", ["a client asks"], "bolt", step=0)
    d.node("r", 330, 40, 210, 76, "processor", "Running", ["max-concurrent"], "play", step=1)
    d.node("q", 330, 182, 210, 76, "terminal", "QUEUED", ["queue-capacity"], "queue", mono=True)
    d.node("x", 330, 324, 210, 76, "terminal", "429", ["client backs off"], "shield", mono=True)
    d.node("f", 660, 40, 190, 76, "terminal", "Finished", ["slot released"], "flag", step=2)
    d.edge("s", "r", fa=0.2, label="slot free")
    d.edge("s", "q", label="QUEUE")
    d.edge("s", "x", fa=0.8, kind="ctl", tone="danger", label="REJECT", label_dx=-14, label_dy=12)
    d.edge("q", "r", sa="t", sb="b", label="slot frees")
    d.edge("q", "x", sa="b", sb="t", tone="danger", label="queue full")
    d.edge("r", "f")
    return d


def ops_upgrade():
    STEPS = (("list", "Read notes", "release notes", "check if behind"),
             ("db", "Back up", "export sessions", "or save the DB"),
             ("queue", "Drain", "new starts: 429", "runs finish"),
             ("box", "Install", "new tag or file", "then restart"),
             ("check", "Verify", "/actuator/info", "run a workflow"))
    CX = [100 + 165 * i for i in range(5)]

    def under(t):
        out = [f'<path d="M{CX[0]} 92H{CX[-1]}" stroke="{t["border"]}" stroke-width="3" '
               'stroke-linecap="round"/>']
        for i, (ic, title, l1, l2) in enumerate(STEPS):
            cx = CX[i]
            col = t["processor"] if i != 2 else t["trigger"]
            out.append(f'<circle cx="{cx}" cy="92" r="22" fill="{t["card"]}" stroke="{col}" '
                       'stroke-width="2"/>')
            out.append(icon(ic, cx - 10, 82, col, scale=1.25))
            out.append(panel(cx - 74, 140, 148, 116, t))
            out.append(text(cx, 172, f"{i + 1}  {title}", t, size=16, weight=700, anchor="middle"))
            out.append(text(cx, 200, l1, t, size=14, weight=400, fill=t["muted_fg"],
                            anchor="middle"))
            out.append(text(cx, 222, l2, t, size=14, weight=400, fill=t["muted_fg"],
                            anchor="middle"))
        # Roll back: from Verify back to Install and the backup, dashed (a command).
        out.append(_arrow(f"M{CX[4]} 270 C{CX[4]} 340 {CX[1]} 340 {CX[1]} 270", t, dashed=True,
                          color=t["danger"], marker="danger"))
        out.append(f'<rect x="{(CX[1] + CX[4]) / 2 - 150:.0f}" y="328" width="300" height="30" '
                   f'rx="15" fill="{t["bg"]}" stroke="{mix(t["danger"], t["bg"], 0.35)}"/>')
        out.append(text((CX[1] + CX[4]) / 2, 348, "roll back: old release, restored backup", t,
                        size=14, weight=500, fill=t["danger"], anchor="middle"))
        return "".join(out)

    return Scene("ops-upgrade",
                 "An upgrade has five steps in order: read the notes, back up what memory holds, "
                 "drain, install and verify. A failed upgrade goes back by reinstalling the old "
                 "release and restoring the backup.", under, w=860, h=430)


def ops_troubleshoot():
    d = Scene("ops-troubleshoot",
              "Start from the run's status. A 429 means a limit, QUEUED means every slot is "
              "taken, TIMED_OUT means one clock ran out, FAILURE means read that node's reason, "
              "and ABORTED means a restart cut the run and it can be resumed.", w=960, h=480)
    d.node("a", 30, 202, 230, 76, "trigger", "Run status", ["workflow status -o json"], "eye",
           step=0)
    leaves = (("429", "A limit was hit", "on-limit, queue, drain", "shield"),
              ("QUEUED", "Every slot is taken", "limits, stuck runs", "queue"),
              ("TIMED_OUT", "A clock ran out", "queue, run or node", "clock"),
              ("FAILURE", "A node failed", "read its reason", "wrench"),
              ("ABORTED", "A restart cut it", "resume the run", "repeat"))
    for i, (st, title, sub, ic) in enumerate(leaves):
        y = 24 + i * 90
        d.node(f"n{i}", 400, y, 530, 66, "terminal", f"{st}: {title}", [sub], ic, step=1)
        d.edge("a", f"n{i}", fa=0.1 + 0.2 * i)
    return d


def ops_write_a_plugin():
    d = Scene("ops-write-a-plugin",
              "A plugin is a Gradle module you register in the build, compile into the server "
              "and restart. It then shows in the plugin catalogue and can be used as a node type "
              "in a workflow. There is no runtime loading.", w=880, h=440)
    d.node("m", 30, 70, 190, 76, "trigger", "Your module", ["plugins/.../shout"], "code", step=0)
    d.node("b", 345, 70, 190, 76, "processor", "Server build", ["boot registers it"], "wrench",
           step=1)
    d.node("s", 660, 70, 190, 76, "processor", "Server", ["restarted"], "server", step=2)
    d.node("c", 660, 270, 190, 76, "terminal", "Catalogue", ["GET /api/plugins"], "list", step=3)
    d.node("w", 345, 270, 190, 76, "terminal", "Workflow node", ["type: SHOUT"], "graph")
    d.edge("m", "b", label="register")
    d.edge("b", "s", label="build, restart")
    d.edge("s", "c", sa="b", sb="t", label="lists it", label_dx=40)
    d.edge("c", "w", sa="l", sb="r", label="pick it")
    return d


# ===================================================================== reference


def ref_workflow_json():
    NODES = (("ticket", "API_TRIGGER", "trigger"), ("classify", "LLM_CALL", "processor"),
             ("done", "CONSOLE_TERMINAL", "terminal"), ("dead", "CONSOLE_TERMINAL", "terminal"))
    LINES = ['"nodes": [',
             *[f'  {{ "nodeId": "{n}", "type": "{ty}" }}' for n, ty, _ in NODES],
             '],', '"edges": [',
             '  { "source": "ticket", "target": "classify" }',
             '  { "source": "classify", "target": "done" }',
             '  { "source": "classify", "target": "dead",',
             '    "sourcePort": "error" }',
             ']']
    BAND = {1: "trigger", 2: "processor", 3: "terminal", 4: "terminal",
            7: "outline", 8: "outline", 9: "danger", 10: "danger"}

    def under(t):
        out = [panel(20, 24, 500, 432, t)]
        for i, line in enumerate(LINES):
            y = 62 + i * 34
            key = BAND.get(i)
            if key:
                col = t[key]
                out.append(f'<rect x="32" y="{y - 20}" width="476" height="28" rx="7" '
                           f'fill="{mix(col, t["card"], 0.13)}"/>')
            out.append(text(44, y, line.replace(" ", "\u00a0") if line.startswith(" ") else line, t, size=14, weight=500, mono=True))
        return "".join(out)

    d = Scene("ref-workflow-json",
              "In a workflow's JSON, each entry of nodes becomes a node of the graph and each "
              "entry of edges becomes a line between two nodes; an edge with sourcePort error "
              "carries the failures of its source.", under, w=960, h=480)
    d.node("ticket", 655, 40, 170, 70, "trigger", "ticket", [], "bolt", step=0)
    d.node("classify", 655, 170, 170, 70, "processor", "classify", [], "spark", step=1)
    d.node("done", 560, 330, 150, 70, "terminal", "done", [], "flag", step=2)
    d.node("dead", 770, 330, 150, 70, "terminal", "dead", [], "inbox")
    d.edge("ticket", "classify", sa="b", sb="t")
    d.edge("classify", "done", sa="b", sb="t", fa=0.3)
    d.edge("classify", "dead", sa="b", sb="t", fa=0.7, tone="danger", label="error",
           label_dx=34)
    return d


def ref_expressions():
    SRC = ("${payload.a.b}", "${metadata.key}", "${start.a.b}", "${context.name}",
           "${env.NAME}", "decrypted:name")

    def under(t):
        out = [panel(20, 30, 260, 284, t),
               text(40, 62, "Where values come from", t, size=15, weight=700)]
        for i, s in enumerate(SRC):
            c, _ = chip(40, 82 + i * 36, s, t, t["terminal"])
            out.append(c)
        out.append(_arrow("M280 172 C310 172 320 172 346 172", t))
        return "".join(out)

    d = Scene("ref-expressions",
              "Two mechanisms read data in a node's config: placeholders such as ${payload.a} "
              "are filled in just before the node runs from the message, the run, the server "
              "environment and secrets, while SpEL expressions are evaluated by plugins such "
              "as BRANCH and FILTER on the message only.", under, w=960, h=480)
    d.node("c", 350, 132, 210, 80, "processor", "Node config", ["${...} text"], "code", step=0)
    d.node("n", 690, 132, 230, 80, "processor", "Node runs", ["with real values"], "play", step=1)
    d.node("m", 350, 350, 210, 80, "terminal", "Message", ["payload, metadata"], "inbox")
    d.node("e", 690, 350, 230, 80, "processor", "BRANCH, FILTER", ["SpEL, read-only"], "branch",
           step=2)
    d.edge("c", "n", label="before it runs")
    d.edge("m", "e", kind="ctl", label="1 s timeout")
    return d


def ref_node_options():
    CARDS = (("Flow control", "how messages move", ("edgeBuffer", "placement", "concurrencyGroup"), 20, 40),
             ("Time limits", "how long a node may take", ("elementTimeoutMs", "deadlineMs", "stallAfterMs"), 20, 280),
             ("Failure handling", "what happens on an error", ("retry", "onError", "trigger", "failureBudget"), 640, 40),
             ("Durability and logging", "what is saved and shown", ("checkpoint", "echoOutput"), 640, 280))

    def under(t):
        out = []
        for title, sub, keys, x, y in CARDS:
            out.append(panel(x, y, 300, 160, t))
            out.append(text(x + 16, y + 34, title, t, size=17, weight=700))
            out.append(text(x + 16, y + 56, sub, t, size=14, weight=400, fill=t["muted_fg"]))
            c, _ = _chips(t, x + 16, y + 78, keys, t["processor"], 270)
            out.append(c)
        for x1, y1, x2, y2 in ((320, 120, 380, 230), (320, 360, 380, 250),
                               (640, 120, 580, 230), (640, 360, 580, 250)):
            out.append(f'<path d="M{x1} {y1} C{(x1 + x2) / 2} {y1} {(x1 + x2) / 2} {y2} {x2} {y2}" '
                       f'fill="none" stroke="{t["outline"]}" stroke-width="1.5" '
                       'stroke-dasharray="5 5" class="march"/>')
        return "".join(out)

    d = Scene("ref-node-options",
              "The engine object in a node's config is read by the engine and never by the "
              "plugin. Its options fall in four groups around the plugin: flow control, time "
              "limits, failure handling, and durability and logging.", under, w=960, h=480)
    d.node("p", 380, 200, 200, 80, "processor", "Plugin", ["never sees engine"], "puzzle", step=0)
    return d


def ref_supervisor_shell():
    def under(t):
        out = [f'<rect x="318" y="110" width="330" height="244" rx="18" '
               f'fill="{mix(t["processor"], t["bg"], 0.07)}" stroke="{t["outline"]}" '
               'stroke-opacity=".7" stroke-dasharray="5 5" class="march"/>',
               text(336, 136, "the shell, enforced by Yukta", t, size=14, weight=500,
                    fill=t["muted_fg"])]
        return "".join(out)

    d = Scene("ref-supervisor-shell",
              "The shell is the fence Yukta enforces around a supervisor's engine: allowedCommands "
              "limits what it may send, initiative what it may send unprompted, operatorAccess "
              "which commands from people it reviews, and canAnswer with maxRisk which input "
              "requests it may answer.", under, w=960, h=480)
    d.node("p", 30, 172, 170, 76, "terminal", "People", ["send commands"], "users")
    d.node("e", 392, 172, 190, 76, "processor", "Engine", ["rules, http, ..."], "eye", step=0)
    d.node("r", 765, 172, 170, 76, "processor", "The run", ["gets commands"], "play", step=1)
    d.node("i", 392, 360, 190, 76, "terminal", "Input request", ["a question"], "inbox")
    d.edge("p", "e", kind="ctl", label="operatorAccess")
    d.edge("e", "r", kind="ctl", label="allowedCommands")
    d.edge("e", "r", sa="t", sb="t", bow=64, kind="ctl", label="initiative", label_t=0.8, label_dy=-2)
    d.edge("e", "i", sa="b", sb="t", kind="ctl", label="canAnswer, maxRisk", label_dx=0)
    return d


def ref_mcp_tools():
    GROUPS = (("Author", "14 tools", "VIEWER to ADMIN", "code"),
              ("Run", "7 tools", "VIEWER, OPERATOR", "play"),
              ("Steer", "3 tools", "OPERATOR", "sliders"),
              ("Supervisors", "19 tools", "VIEWER, ADMIN", "eye"),
              ("Inputs", "3 tools", "VIEWER, OPERATOR", "inbox"),
              ("Credentials", "2 tools", "ADMIN", "shield"),
              ("Operate", "5 tools", "VIEWER, ADMIN", "pulse"))
    d = Scene("ref-mcp-tools",
              "An AI client calls Yukta's MCP endpoint with an API key, and the 53 tools fall "
              "in seven groups: author, run, steer, supervisors, inputs, credentials and operate. "
              "The key's role decides which tools of each group it may call.", w=960, h=480)
    d.node("a", 30, 202, 190, 76, "trigger", "AI client", ["with an API key"], "spark", step=0)
    d.node("m", 320, 202, 190, 76, "processor", "/mcp", ["53 tools, 3 roles"], "plug", step=1)
    for i, (title, count, roles, ic) in enumerate(GROUPS):
        d.node(f"g{i}", 620, 14 + i * 66, 310, 56, "terminal", title, [f"{count}: {roles}"], ic,
               step=2)
        d.edge("m", f"g{i}", fa=0.1 + 0.8 * i / 6)
    d.edge("a", "m")
    return d


def ref_client_parity():
    d = Scene("ref-client-parity",
              "REST, MCP, the yukta-cli command line and the web UI are four ways into the same "
              "server, and the same API key and role apply whichever you use.", w=880, h=440)
    for i, (title, sub, ic) in enumerate((("REST", "/api endpoints", "globe"),
                                          ("MCP", "tools for agents", "spark"),
                                          ("CLI", "yukta-cli", "terminal"),
                                          ("Web UI", "pages", "eye"))):
        d.node(f"c{i}", 30, 38 + i * 96, 220, 70, "trigger", title, [sub], ic, step=0)
        d.edge(f"c{i}", "s", fb=0.2 + 0.2 * i)
    d.node("s", 560, 150, 290, 140, "processor", "Yukta server", ["one API key", "one role check",
                                                                 "one audit"], "server", step=1)
    return d


def ref_plugin_contract():
    ROLES = (("Trigger", "start(config)", "Flux of messages", "trigger"),
             ("Processor", "process(input, config)", "Flux of messages", "processor"),
             ("Terminal", "consume(input, config)", "Mono<Void>", "terminal"))

    def under(t):
        out = [panel(30, 232, 800, 170, t),
               text(52, 264, "The last step depends on the role", t, size=15, weight=700)]
        for i, (role, sig, ret, key) in enumerate(ROLES):
            y = 282 + i * 38
            c, _ = chip(52, y, role, t, t[key])
            out.append(c)
            out.append(text(180, y + 16, sig, t, size=15, weight=500, mono=True))
            out.append(text(470, y + 16, "returns", t, size=14, weight=400, fill=t["muted_fg"]))
            out.append(text(545, y + 16, ret, t, size=15, weight=500, mono=True))
        out.append(_arrow("M745 136 C745 170 745 190 745 224", t))
        return "".join(out)

    d = Scene("ref-plugin-contract",
              "A plugin's operations run in a fixed order: validateConfig and validateInContext "
              "when the workflow is validated, initialize and prepare when it is compiled, "
              "forNode when a run is assembled, then start, process or consume while it runs.",
              under, w=860, h=430)
    d.node("v", 30, 60, 190, 76, "processor", "Validation", ["validateConfig", "validateInContext"],
           "shield", step=0)
    d.node("c", 255, 60, 170, 76, "processor", "Compile", ["initialize", "prepare"], "code",
           step=1)
    d.node("a", 460, 60, 170, 76, "processor", "Assemble", ["forNode", "once per node"],
           "puzzle", step=2)
    d.node("r", 675, 60, 155, 76, "trigger", "Run", ["by role"], "play", step=3)
    d.edge("v", "c")
    d.edge("c", "a")
    d.edge("a", "r")
    return d


def ref_status_values():
    GROUPS = (("Done", ("SUCCESS",), "processor"),
              ("Failed", ("FAILURE",), "danger"),
              ("Cut short", ("STOPPED", "CANCELLED", "TIMED_OUT", "ABORTED"), "terminal"),
              ("Never ran", ("SKIPPED", "UPSTREAM_FAILED"), "terminal"))

    def under(t):
        out = [panel(600, 24, 380, 452, t),
               text(620, 58, "One final status", t, size=17, weight=700)]
        y = 80
        for title, labs, key in GROUPS:
            c, bottom = _chips(t, 740, y + 8, labs, t[key], 224)
            out.append(text(620, y + 25, title, t, size=15, weight=600))
            out.append(c)
            y = bottom + 44
            out.append(f'<path d="M616 {y - 12}H964" stroke="{t["border"]}" stroke-opacity=".7"/>')
        out.append(_arrow("M514 250 H592", t))
        return "".join(out)

    d = Scene("ref-status-values",
              "A run or node status only moves forward from PENDING to QUEUED to RUNNING, and "
              "then ends in exactly one final status: done, failed, cut short or never run.",
              under, w=1000, h=500)
    d.node("p", 20, 212, 150, 76, "terminal", "PENDING", [], "clock", mono=True, step=0)
    d.node("q", 190, 212, 150, 76, "terminal", "QUEUED", [], "queue", mono=True, step=1)
    d.node("r", 360, 212, 150, 76, "trigger", "RUNNING", [], "play", mono=True, live=True)
    d.edge("p", "q")
    d.edge("q", "r")
    return d


FNS = (ops_deploy, ops_persist_sessions, ops_secure_the_server, ops_observe_runs,
       ops_tune_capacity, ops_upgrade, ops_troubleshoot, ops_write_a_plugin,
       ref_workflow_json, ref_expressions, ref_node_options, ref_supervisor_shell,
       ref_mcp_tools, ref_client_parity, ref_plugin_contract, ref_status_values)


def main():
    for fn in FNS:
        fn().save()


if __name__ == "__main__":
    main()
