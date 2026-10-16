# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Generates the animated SVG diagrams used by the docs (stdlib only).

Run from anywhere:  python3 docs/images/diagrams/generate.py

Each diagram is a self-contained SVG: a dark "glass" panel with glowing, colour-coded nodes,
flowing dashed edges and light dots travelling along them. Motion uses CSS and SMIL, so it plays
inside a plain <img> tag. Users who prefer reduced motion get a static, fully visible frame.
"""

import html
import math
import pathlib

OUT = pathlib.Path(__file__).resolve().parent

# name -> (light stop, deep stop)
PAL = {
    "orange": ("#FFB46B", "#F2641A"),
    "blue": ("#86B4FF", "#2F6BFF"),
    "green": ("#52E3A4", "#059669"),
    "rose": ("#FF8096", "#E11D48"),
    "violet": ("#BCA3FF", "#7C3AED"),
    "cyan": ("#63EBF7", "#0891B2"),
    "amber": ("#FFD66B", "#D97706"),
    "slate": ("#B4C0DC", "#5B6B8F"),
}

SANS = "Inter, 'Segoe UI', system-ui, -apple-system, Helvetica, Arial, sans-serif"
MONO = "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

CSS = """
.node{opacity:0;animation:rise .8s cubic-bezier(.2,.8,.2,1) forwards;animation-delay:var(--d,0s)}
@keyframes rise{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}
.edge,.pill{opacity:0;animation:fade .9s ease forwards;animation-delay:var(--d,0s)}
@keyframes fade{to{opacity:1}}
.glow{opacity:.2;animation:breathe 5.2s ease-in-out infinite;animation-delay:var(--b,0s)}
@keyframes breathe{0%,100%{opacity:.14}50%{opacity:.72}}
.flow{stroke-dasharray:7 10;animation:flow 1.2s linear infinite}
.flow-ctl{stroke-dasharray:3 9;animation:flow-ctl 1.4s linear infinite}
@keyframes flow{to{stroke-dashoffset:-17}}
@keyframes flow-ctl{to{stroke-dashoffset:-12}}
.blob{animation:drift 16s ease-in-out infinite alternate}
.blob.b2{animation-duration:21s;animation-direction:alternate-reverse}
@keyframes drift{to{transform:translate(46px,28px)}}
.sheen{animation:sheen 7s ease-in-out infinite}
@keyframes sheen{0%,100%{opacity:.55}50%{opacity:1}}
@media (prefers-reduced-motion:reduce){
  *{animation:none!important}
  .node,.edge,.pill{opacity:1!important}
  .glow{opacity:.35!important}
  .dot{display:none}
}
"""


def esc(text):
    return html.escape(str(text), quote=True)


class Diagram:
    def __init__(self, name, width, height, alt):
        self.name = name
        self.w = width
        self.h = height
        self.alt = alt
        self.nodes = {}
        self.node_order = []
        self.edges = []
        self.captions = []
        self.legend_items = []
        self.used_colors = set()

    # ---------------------------------------------------------------- model

    def node(self, nid, x, y, w, h, color, title, sub=(), tag=None, mono=False, shape="rect"):
        self.nodes[nid] = dict(
            id=nid, x=x, y=y, w=w, h=h, color=color, title=title, sub=list(sub), tag=tag,
            mono=mono, shape=shape, idx=len(self.node_order),
        )
        self.node_order.append(nid)
        self.used_colors.add(color)

    def edge(self, a, b, color, kind="data", sa="r", sb="l", fa=0.5, fb=0.5, label=None,
             label_t=0.5, label_dx=0, label_dy=0, bow=None, dots=1, speed=2.6):
        self.edges.append(dict(
            a=a, b=b, color=color, kind=kind, sa=sa, sb=sb, fa=fa, fb=fb, label=label,
            label_t=label_t, label_dx=label_dx, label_dy=label_dy, bow=bow, dots=dots,
            speed=speed, idx=len(self.edges),
        ))
        self.used_colors.add(color)

    def caption(self, x, y, text, anchor="middle"):
        self.captions.append((x, y, text, anchor))

    def legend(self, x, y):
        self.legend_items = [(x, y)]

    # ---------------------------------------------------------------- geometry

    @staticmethod
    def anchor(n, side, frac):
        x, y, w, h = n["x"], n["y"], n["w"], n["h"]
        if side == "l":
            return x, y + h * frac, (-1, 0)
        if side == "r":
            return x + w, y + h * frac, (1, 0)
        if side == "t":
            return x + w * frac, y, (0, -1)
        return x + w * frac, y + h, (0, 1)

    def path(self, e):
        na, nb = self.nodes[e["a"]], self.nodes[e["b"]]
        x1, y1, d1 = self.anchor(na, e["sa"], e["fa"])
        x2, y2, d2 = self.anchor(nb, e["sb"], e["fb"])
        # Stop short of the target so the arrowhead tip touches the border, not the interior.
        if e["bow"] is not None:
            k1 = k2 = e["bow"]
        else:
            span = abs(x2 - x1) if d1[0] else abs(y2 - y1)
            k1 = k2 = max(38.0, span * 0.5)
        c1 = (x1 + d1[0] * k1, y1 + d1[1] * k1)
        c2 = (x2 + d2[0] * k2, y2 + d2[1] * k2)
        d = f"M{x1:.1f},{y1:.1f} C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {x2:.1f},{y2:.1f}"
        mid = bezier((x1, y1), c1, c2, (x2, y2), e["label_t"])
        return d, mid

    # ---------------------------------------------------------------- rendering

    def render_defs(self):
        out = ["<defs>"]
        out.append(
            '<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
            '<stop offset="0" stop-color="#0B1226"/><stop offset="1" stop-color="#121B3B"/>'
            "</linearGradient>"
        )
        out.append(
            '<linearGradient id="bar" x1="0" x2="1"><stop offset="0" stop-color="#F2641A"/>'
            '<stop offset=".22" stop-color="#F2641A"/><stop offset=".22" stop-color="#2F6BFF"/>'
            '<stop offset=".8" stop-color="#6FA2FF"/><stop offset="1" stop-color="#9B7BFF"/>'
            "</linearGradient>"
        )
        out.append(
            '<pattern id="grid" width="26" height="26" patternUnits="userSpaceOnUse">'
            '<circle cx="1.5" cy="1.5" r="1.1" fill="#8FA4D8" opacity=".16"/></pattern>'
        )
        for nm, c in (("a", "#2F6BFF"), ("b", "#F2641A"), ("c", "#2F6BFF")):
            out.append(
                f'<radialGradient id="blob-{nm}"><stop offset="0" stop-color="{c}" '
                'stop-opacity=".34"/><stop offset="1" stop-color="' + c + '" stop-opacity="0"/>'
                "</radialGradient>"
            )
        out.append(f'<clipPath id="card"><rect width="{self.w}" height="{self.h}" rx="24"/></clipPath>')
        out.append('<filter id="blur" x="-50%" y="-50%" width="200%" height="200%">'
                   '<feGaussianBlur stdDeviation="7"/></filter>')
        out.append('<filter id="blur-s" x="-100%" y="-100%" width="300%" height="300%">'
                   '<feGaussianBlur stdDeviation="3.2"/></filter>')
        out.append('<filter id="shadow" x="-20%" y="-20%" width="140%" height="160%">'
                   '<feDropShadow dx="0" dy="8" stdDeviation="9" flood-color="#000" '
                   'flood-opacity=".38"/></filter>')
        for c in sorted(self.used_colors):
            lo, hi = PAL[c]
            out.append(
                f'<linearGradient id="s-{c}" x1="0" y1="0" x2="1" y2="1">'
                f'<stop offset="0" stop-color="{lo}"/><stop offset="1" stop-color="{hi}"/>'
                "</linearGradient>"
            )
            out.append(
                f'<linearGradient id="g-{c}" x1="0" y1="0" x2="0" y2="1">'
                f'<stop offset="0" stop-color="{lo}" stop-opacity=".30"/>'
                f'<stop offset="1" stop-color="{hi}" stop-opacity=".09"/></linearGradient>'
            )
            out.append(
                f'<marker id="m-{c}" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="11" '
                f'markerHeight="11" markerUnits="userSpaceOnUse" orient="auto">'
                f'<path d="M1,1.5 L11,6 L1,10.5 L3.8,6 z" fill="{lo}"/></marker>'
            )
        out.append("</defs>")
        return "".join(out)

    def render_node(self, n):
        c = n["color"]
        lo, hi = PAL[c]
        x, y, w, h = n["x"], n["y"], n["w"], n["h"]
        d = 0.12 + n["idx"] * 0.09
        b = n["idx"] * 0.55
        g = [f'<g class="node" style="--d:{d:.2f}s">']
        shape = n["shape"]
        if shape == "diamond":
            pts = f"{x + w / 2},{y} {x + w},{y + h / 2} {x + w / 2},{y + h} {x},{y + h / 2}"
            g.append(f'<polygon class="glow" style="--b:{b:.2f}s" points="{pts}" fill="none" '
                     f'stroke="{lo}" stroke-width="7" filter="url(#blur)"/>')
            g.append(f'<polygon points="{pts}" fill="#111A33" filter="url(#shadow)"/>')
            g.append(f'<polygon points="{pts}" fill="url(#g-{c})" stroke="url(#s-{c})" '
                     'stroke-width="1.8" stroke-linejoin="round"/>')
        elif shape == "cylinder":
            ry = 13
            body = (f"M{x},{y + ry} A{w / 2},{ry} 0 0 1 {x + w},{y + ry} V{y + h - ry} "
                    f"A{w / 2},{ry} 0 0 1 {x},{y + h - ry} Z")
            g.append(f'<path class="glow" style="--b:{b:.2f}s" d="{body}" fill="none" '
                     f'stroke="{lo}" stroke-width="7" filter="url(#blur)"/>')
            g.append(f'<path d="{body}" fill="#111A33" filter="url(#shadow)"/>')
            g.append(f'<path d="{body}" fill="url(#g-{c})" stroke="url(#s-{c})" stroke-width="1.8"/>')
            g.append(f'<ellipse cx="{x + w / 2}" cy="{y + ry}" rx="{w / 2}" ry="{ry}" '
                     f'fill="url(#g-{c})" stroke="url(#s-{c})" stroke-width="1.8"/>')
        else:
            g.append(f'<rect class="glow" style="--b:{b:.2f}s" x="{x}" y="{y}" width="{w}" '
                     f'height="{h}" rx="16" fill="none" stroke="{lo}" stroke-width="7" '
                     'filter="url(#blur)"/>')
            g.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="#111A33" '
                     'filter="url(#shadow)"/>')
            g.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="url(#g-{c})" '
                     f'stroke="url(#s-{c})" stroke-width="1.8"/>')
            # Glassy top highlight.
            g.append(f'<path d="M{x + 14},{y + 1.5} H{x + w - 14}" stroke="#fff" '
                     'stroke-opacity=".28" stroke-width="1.2" stroke-linecap="round"/>')

        # Text block, vertically centred.
        lines = []
        if n["tag"]:
            lines.append(("tag", n["tag"], 10.5, 14))
        lines.append(("title", n["title"], 15.5 if not n["mono"] else 13.5, 21))
        for s in n["sub"]:
            lines.append(("sub", s, 12, 16))
        total = sum(l[3] for l in lines)
        cy = y + h / 2 - total / 2
        cx = x + w / 2
        for kind, text, size, lh in lines:
            base = cy + lh * 0.78
            cy += lh
            if kind == "tag":
                g.append(f'<text x="{cx}" y="{base:.1f}" text-anchor="middle" font-family="{MONO}" '
                         f'font-size="{size}" font-weight="600" letter-spacing="1.4" fill="{lo}">'
                         f"{esc(text)}</text>")
            elif kind == "title":
                fam = MONO if n["mono"] else SANS
                g.append(f'<text x="{cx}" y="{base:.1f}" text-anchor="middle" font-family="{fam}" '
                         f'font-size="{size}" font-weight="650" fill="#F4F7FF">{esc(text)}</text>')
            else:
                g.append(f'<text x="{cx}" y="{base:.1f}" text-anchor="middle" font-family="{SANS}" '
                         f'font-size="{size}" fill="#A7B6D8">{esc(text)}</text>')
        g.append("</g>")
        return "".join(g)

    def render_edge(self, e):
        lo, hi = PAL[e["color"]]
        d, mid = self.path(e)
        pid = f"p{e['idx']}"
        delay = 0.7 + e["idx"] * 0.07
        ctl = e["kind"] == "ctl"
        parts = [f'<g class="edge" style="--d:{delay:.2f}s">']
        base_dash = ' stroke-dasharray="2 7"' if ctl else ""
        parts.append(f'<path id="{pid}" d="{d}" fill="none" stroke="{lo}" stroke-opacity="{.5 if ctl else .5}" '
                     f'stroke-width="2" stroke-linecap="round"{base_dash} marker-end="url(#m-{e["color"]})"/>')
        cls = "flow-ctl" if ctl else "flow"
        parts.append(f'<path class="{cls}" d="{d}" fill="none" stroke="{lo}" stroke-width="2.4" '
                     'stroke-linecap="round" stroke-opacity=".95"/>')
        for i in range(e["dots"]):
            begin = f"{(e['idx'] * 0.37 + i * e['speed'] / e['dots']) % e['speed']:.2f}s"
            dur = f"{e['speed']}s"
            fill = "none" if ctl else lo
            parts.append(
                '<g class="dot" opacity="0">'
                f'<circle r="8" fill="{lo}" opacity=".55" filter="url(#blur-s)"/>'
                f'<circle r="3.6" fill="#fff"/><circle r="2.2" fill="{lo}"/>'
                f'<animateMotion dur="{dur}" begin="{begin}" repeatCount="indefinite" '
                'calcMode="spline" keyTimes="0;1" keySplines=".45 .05 .55 .95">'
                f'<mpath href="#{pid}"/></animateMotion>'
                f'<animate attributeName="opacity" values="0;1;1;0" keyTimes="0;.12;.85;1" '
                f'dur="{dur}" begin="{begin}" repeatCount="indefinite"/></g>'
            )
        parts.append("</g>")
        return "".join(parts), mid

    def render_label(self, e, mid):
        text = e["label"]
        w = len(text) * 6.4 + 20
        x = mid[0] + e["label_dx"]
        y = mid[1] + e["label_dy"]
        lo, _ = PAL[e["color"]]
        delay = 1.0 + e["idx"] * 0.07
        return (
            f'<g class="pill" style="--d:{delay:.2f}s">'
            f'<rect x="{x - w / 2:.1f}" y="{y - 11:.1f}" width="{w:.1f}" height="22" rx="11" '
            f'fill="#0A1024" stroke="{lo}" stroke-opacity=".6"/>'
            f'<text x="{x:.1f}" y="{y + 4:.1f}" text-anchor="middle" font-family="{SANS}" '
            f'font-size="11.5" font-weight="600" fill="{lo}">{esc(text)}</text></g>'
        )

    def render_legend(self):
        if not self.legend_items:
            return ""
        x, y = self.legend_items[0]
        lo, _ = PAL["blue"]
        return (
            '<g class="pill" style="--d:1.4s">'
            f'<path d="M{x},{y} H{x + 34}" stroke="{lo}" stroke-width="2.4" stroke-linecap="round" '
            'stroke-opacity=".95"/>'
            f'<text x="{x + 44}" y="{y + 4}" font-family="{SANS}" font-size="12" fill="#A7B6D8">'
            "data flow</text>"
            f'<path d="M{x + 140},{y} H{x + 174}" stroke="{lo}" stroke-width="2.4" '
            'stroke-linecap="round" stroke-dasharray="2 7"/>'
            f'<text x="{x + 184}" y="{y + 4}" font-family="{SANS}" font-size="12" fill="#A7B6D8">'
            "commands and events</text></g>"
        )

    def render(self):
        w, h = self.w, self.h
        s = [
            f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
            f'viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-label="{esc(self.alt)}">',
            f"<title>{esc(self.alt)}</title>",
            f"<style>{CSS}</style>",
            self.render_defs(),
            '<g clip-path="url(#card)">',
            f'<rect width="{w}" height="{h}" fill="url(#bg)"/>',
            f'<rect width="{w}" height="{h}" fill="url(#grid)"/>',
            f'<circle class="blob" cx="{w * .12}" cy="{h * .18}" r="{h * .62}" fill="url(#blob-a)"/>',
            f'<circle class="blob b2" cx="{w * .86}" cy="{h * .86}" r="{h * .66}" fill="url(#blob-b)"/>',
            f'<circle class="blob" cx="{w * .5}" cy="{h * .02}" r="{h * .34}" fill="url(#blob-c)" '
            'opacity=".55"/>',
            f'<rect class="sheen" width="{w}" height="3.5" fill="url(#bar)"/>',
            "</g>",
            f'<rect x=".75" y=".75" width="{w - 1.5}" height="{h - 1.5}" rx="23.5" fill="none" '
            'stroke="#fff" stroke-opacity=".10" stroke-width="1.5"/>',
        ]
        labels = []
        for e in self.edges:
            svg, mid = self.render_edge(e)
            s.append(svg)
            if e["label"]:
                labels.append(self.render_label(e, mid))
        for nid in self.node_order:
            s.append(self.render_node(self.nodes[nid]))
        s.extend(labels)
        for x, y, text, anchor in self.captions:
            s.append(
                f'<text class="pill" style="--d:1.3s" x="{x}" y="{y}" text-anchor="{anchor}" '
                f'font-family="{SANS}" font-size="12.5" font-style="italic" fill="#8EA0C8">'
                f"{esc(text)}</text>"
            )
        s.append(self.render_legend())
        s.append(
            f'<text x="{w - 22}" y="{h - 16}" text-anchor="end" font-family="{SANS}" '
            'font-size="11" font-weight="700" letter-spacing="3" fill="#fff" opacity=".22">YUKTA</text>'
        )
        s.append("</svg>")
        return "\n".join(s) + "\n"

    def save(self):
        path = OUT / f"{self.name}.svg"
        path.write_text(self.render(), encoding="utf-8")
        print("wrote", path.relative_to(OUT.parent.parent.parent))


def bezier(p0, p1, p2, p3, t):
    u = 1 - t
    return (
        u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
        u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1],
    )


# ===================================================================== diagrams


def workflow_anatomy():
    d = Diagram("workflow-anatomy", 1010, 350,
                "A trigger starts the flow, a processor calls an API, a second processor transforms "
                "the result and a terminal notifies. A failed call goes to a dead-letter terminal.")
    d.node("t", 40, 70, 180, 88, "orange", "Trigger", ["starts the flow"], tag="TRIGGER")
    d.node("p1", 290, 70, 180, 88, "blue", "Processor", ["call an API"], tag="PROCESSOR")
    d.node("p2", 540, 70, 180, 88, "blue", "Processor", ["transform"], tag="PROCESSOR")
    d.node("e", 790, 70, 180, 88, "green", "Terminal", ["notify"], tag="TERMINAL")
    d.node("d", 290, 230, 180, 88, "rose", "Terminal", ["dead letter"], tag="TERMINAL")
    d.edge("t", "p1", "orange")
    d.edge("p1", "p2", "green", label="success")
    d.edge("p1", "d", "rose", sa="b", sb="t", label="error")
    d.edge("p2", "e", "blue")
    d.save()


def status_model():
    d = Diagram("status-model", 1010, 230,
                "Run and node status only move forward: PENDING, QUEUED, RUNNING, then a terminal "
                "status.")
    d.node("p", 40, 56, 180, 90, "amber", "PENDING", ["created, not started"], mono=True)
    d.node("q", 290, 56, 180, 90, "cyan", "QUEUED", ["waiting for a slot"], mono=True)
    d.node("r", 540, 56, 180, 90, "blue", "RUNNING", ["first input arrived"], mono=True)
    d.node("t", 790, 56, 180, 90, "green", "terminal status", ["final, never reopens"])
    d.edge("p", "q", "amber")
    d.edge("q", "r", "cyan")
    d.edge("r", "t", "blue")
    d.caption(505, 192, "A status that would step back is ignored.")
    d.save()


def architecture():
    d = Diagram("architecture", 960, 545,
                "Clients enter through admission and definition into the stream engine, which runs "
                "plugins and feeds observation and durability. A control plane sends ordered "
                "commands to the engine, and supervisors read the event feed to issue commands.")
    cw, h = 230, 92
    c1, c2, c3 = 40, 365, 690
    d.node("cl", c1, 40, cw, h, "slate", "Clients", ["REST · web UI ·", "MCP agents · CLI"])
    d.node("en", c2, 40, cw, h, "amber", "Entry and admission", ["limits, queue,", "idempotent start"])
    d.node("df", c3, 40, cw, h, "violet", "Definition", ["validate and", "prepare the graph"])
    d.node("su", c1, 215, cw, h, "cyan", "Supervisors", ["rules, a model", "or your own code"])
    d.node("cp", c2, 215, cw, h, "rose", "Control plane", ["ordered, reviewed,", "audited commands"])
    d.node("sg", c3, 207, cw, 108, "blue", "Stream engine", ["one stream per edge,", "policies per node"],
           tag="CORE")
    d.node("ob", c1, 405, cw, h, "orange", "Observation", ["progress, logs,", "results, metrics"])
    d.node("du", c2, 405, cw, h, "slate", "Durability", ["execution log,", "checkpoints"])
    d.node("pl", c3, 405, cw, h, "green", "Plugins", ["local, or on", "remote workers"])
    d.edge("cl", "en", "slate")
    d.edge("en", "df", "amber")
    d.edge("df", "sg", "violet", sa="b", sb="t")
    d.edge("sg", "pl", "green", sa="b", sb="t", fa=0.82, fb=0.82)
    d.edge("sg", "du", "slate", sa="b", sb="t", fa=0.5, fb=0.5)
    d.edge("sg", "ob", "orange", sa="b", sb="t", fa=0.16, fb=0.72, bow=40)
    d.edge("su", "cp", "rose", kind="ctl", label="commands")
    d.edge("cp", "sg", "rose", kind="ctl", label="commands")
    d.edge("ob", "su", "cyan", kind="ctl", sa="t", sb="b", fa=0.28, fb=0.28, label="event feed",
           label_dx=0)
    d.legend(40, 521)
    d.save()


def backpressure():
    d = Diagram("backpressure", 960, 395,
                "A trigger feeds a fast node, which feeds a slow node and another fast node. The "
                "slow node paces the fast node and the trigger.")
    d.node("t", 40, 203, 170, 90, "orange", "Trigger", ["emits messages"], tag="TRIGGER")
    d.node("a", 290, 203, 170, 90, "blue", "Fast node", ["waits when full"], tag="PROCESSOR")
    d.node("b", 600, 111, 190, 90, "amber", "Slow node", ["takes its time"], tag="PROCESSOR")
    d.node("c", 600, 271, 190, 90, "green", "Fast node", ["keeps up"], tag="PROCESSOR")
    d.edge("t", "a", "orange")
    d.edge("a", "b", "blue", fb=0.5)
    d.edge("a", "c", "blue")
    d.edge("b", "a", "rose", kind="ctl", sa="t", sb="t", fa=0.5, fb=0.5, bow=80,
           label="slow node paces A and the trigger", label_t=0.5, label_dy=-14, dots=2, speed=3.4)
    d.save()


def agent_loop():
    d = Diagram("agent-loop", 1070, 420,
                "An agent workflow lists MCP tools, asks the model, branches on whether it asked for "
                "a tool, maps the arguments, calls the tool and asks the model for a final answer.")
    w, h = 160, 84
    xs = [30, 240, 450, 660, 870]
    top, bot = 70, 255
    d.node("l", xs[0], top, w, h, "blue", "MCP_LIST_TOOLS", mono=True, tag="PROCESSOR")
    d.node("p", xs[1], top, w, h, "violet", "LLM_CALL", ["tools"], mono=True, tag="PROCESSOR")
    d.node("b", xs[2] - 8, top - 20, w + 16, h + 40, "amber", "BRANCH", ["asked for a tool?"],
           mono=True, shape="diamond")
    d.node("m", xs[3], top, w, h, "cyan", "MAPPER", mono=True, tag="PROCESSOR")
    d.node("a", xs[4], top, w, h, "blue", "MCP_TOOL_CALL", mono=True, tag="PROCESSOR")
    d.node("f", xs[4], bot, w, h, "violet", "LLM_CALL", ["final answer"], mono=True, tag="PROCESSOR")
    d.node("e", xs[3], bot, w, h, "green", "Terminal", ["ends the run"], tag="END")
    d.node("dd", xs[2], bot, w, h, "green", "Terminal", ["ends the run"], tag="END")
    d.edge("l", "p", "blue")
    d.edge("p", "b", "violet")
    d.edge("b", "m", "green", label="yes", label_t=0.45, label_dy=-20)
    d.edge("m", "a", "cyan")
    d.edge("a", "f", "blue", sa="b", sb="t")
    d.edge("f", "e", "violet", sa="l", sb="r")
    d.edge("b", "dd", "rose", sa="b", sb="t", label="no", label_dx=18)
    d.save()


def scheduling():
    d = Diagram("scheduling", 980, 330,
                "A workflow is started once and holds one open execution. SCHEDULE_TRIGGER emits a "
                "tick into it, and the nodes handle every tick as a message.")
    d.node("s", 40, 52, 200, 88, "cyan", "Start once", ["one REST, CLI or", "MCP call"])
    d.node("t", 40, 196, 200, 88, "orange", "SCHEDULE_TRIGGER", ["tick 1, 2, 3 ..."], mono=True,
           tag="TRIGGER")
    d.node("x", 390, 124, 200, 88, "blue", "One open execution", ["holds one slot"])
    d.node("g", 740, 124, 200, 88, "violet", "Nodes", ["handle each tick", "as a message"])
    d.edge("s", "x", "cyan", fb=0.3, fa=0.5)
    d.edge("t", "x", "orange", fb=0.7, dots=3, speed=2.4)
    d.edge("x", "g", "blue", dots=2, speed=2.0)
    d.save()


def module_map():
    d = Diagram("module-map", 1070, 505,
                "Clients reach the web, mcp and ui modules, which call core. Core uses plugin-api "
                "and plugins, messaging, persistence-postgres and workers. The boot module wires "
                "everything into one Spring Boot app.")
    d.node("boot", 300, 30, 260, 72, "rose", "boot", ["one Spring Boot app"], mono=True)
    d.node("cl", 30, 207, 200, 86, "slate", "Clients", ["REST, web UI,", "MCP agents, CLI"])
    d.node("ed", 290, 207, 180, 86, "blue", "web, mcp, ui", ["edge modules"], mono=True)
    d.node("co", 530, 197, 200, 106, "violet", "core", ["engine and", "control plane"], mono=True)
    d.node("pl", 810, 40, 230, 72, "green", "plugin-api", ["and plugins"], mono=True)
    d.node("ms", 810, 150, 230, 72, "amber", "messaging", mono=True)
    d.node("st", 810, 260, 230, 72, "cyan", "persistence-postgres", mono=True)
    d.node("wk", 810, 370, 230, 72, "orange", "workers", mono=True)
    d.edge("cl", "ed", "slate")
    d.edge("ed", "co", "blue")
    d.edge("co", "pl", "green", fa=0.15, fb=0.5)
    d.edge("co", "ms", "amber", fa=0.38, fb=0.5)
    d.edge("co", "st", "cyan", fa=0.62, fb=0.5)
    d.edge("co", "wk", "orange", fa=0.85, fb=0.5)
    d.edge("pl", "ms", "green", sa="b", sb="t", fa=0.82, fb=0.82, bow=24, speed=1.8)
    d.edge("boot", "ed", "rose", kind="ctl", sa="b", sb="t", fa=0.3, fb=0.5, label="wires together",
           label_t=0.55, label_dx=-34)
    d.edge("boot", "co", "rose", kind="ctl", sa="b", sb="t", fa=0.75, fb=0.5)
    d.save()


def feature_map():
    d = Diagram("feature-map", 1100, 610,
                "Feature groups of the core: entry and admission load a definition, the stream "
                "engine runs plugins and sub-workflows and emits events to observation, which logs "
                "to durability and persistence. Supervisors send commands through the control "
                "plane to the engine.")
    cw, h = 215, 94
    cx = [40, 315, 590, 865]
    r1, r2, r3 = 40, 235, 430
    d.node("adm", cx[0], r1, cw, h, "amber", "Entry and Admission", ["slots, queue"], tag="ADM")
    d.node("def", cx[1], r1, cw, h, "violet", "Definition", ["validate, compile"], tag="DEF")
    d.node("eng", cx[2], r1, cw, h, "blue", "Stream Engine", ["nodes, policies"], tag="ENG")
    d.node("ext", cx[3], r1, cw, h, "green", "Plugins / Workers", ["node work"], tag="EXT · SCL")
    d.node("obs", cx[1], r2, cw, h, "orange", "Observation", ["tracker, metrics"], tag="OBS")
    d.node("ctl", cx[2], r2, cw, h, "rose", "Control Plane", ["ordered, audited"], tag="CTL")
    d.node("sub", cx[3], r2, cw, h, "cyan", "Sub-workflows", ["child runs"], tag="ENG-07")
    d.node("per", cx[0], r3, cw, h, "slate", "Persistence", ["files · PostgreSQL"])
    d.node("dur", cx[1], r3, cw, h, "slate", "Durability", ["log, checkpoints"], tag="DUR")
    d.node("sup", cx[2], r3, cw, h, "cyan", "Supervisors", ["rules, AI, code"], tag="CTL-07")
    d.node("ha", cx[3], r3, cw, h, "green", "HA and Security", ["keys"], tag="SCL-02 · SEC")
    d.edge("adm", "def", "amber", label="load")
    d.edge("def", "eng", "violet", label="graph")
    d.edge("eng", "ext", "blue", label="stream")
    d.edge("eng", "sub", "cyan", sa="b", sb="t", fa=0.88, fb=0.5, bow=44, label="child run",
           label_dx=22, label_dy=10)
    d.edge("eng", "obs", "orange", sa="b", sb="t", fa=0.12, fb=0.5, bow=44, label="events",
           label_dx=-22, label_dy=10)
    d.edge("obs", "dur", "orange", sa="b", sb="t", label="log", label_dx=22)
    d.edge("dur", "per", "slate", sa="l", sb="r", label="store")
    d.edge("sup", "ctl", "rose", kind="ctl", sa="t", sb="b", label="cmds", label_dx=26)
    d.edge("ctl", "eng", "rose", kind="ctl", sa="t", sb="b", label="commands", label_dx=36)
    d.edge("obs", "ctl", "orange", kind="ctl", label="feed")
    d.legend(40, 585)
    d.save()


def remote_workers():
    d = Diagram("remote-workers", 960, 350,
                "The control plane opens one channel per attempt to each worker. Workers register "
                "and renew their lease with the control plane.")
    d.node("cp", 40, 70, 230, 210, "blue", "Control plane", ["admits and tracks runs"])
    d.node("w1", 690, 40, 230, 86, "green", "Worker A", ["pool default"], tag="WORKER")
    d.node("w2", 690, 224, 230, 86, "green", "Worker B", ["pool default"], tag="WORKER")
    d.edge("cp", "w1", "blue", fa=0.15, fb=0.25, label="one channel per attempt", label_dy=-14,
           dots=2)
    d.edge("w1", "cp", "cyan", kind="ctl", sa="l", sb="r", fa=0.8, fb=0.33,
           label="register, renew lease", label_dy=16)
    d.edge("cp", "w2", "blue", fa=0.85, fb=0.75, dots=2)
    d.edge("w2", "cp", "cyan", kind="ctl", sa="l", sb="r", fa=0.25, fb=0.67)
    d.save()


def high_availability():
    d = Diagram("high-availability", 960, 380,
                "Clients talk to the leader. A standby answers 503. Both servers use one PostgreSQL "
                "lease row: the leader renews it and the standby waits for it to expire.")
    d.node("c", 40, 150, 170, 88, "cyan", "Clients", ["REST, UI, agents"])
    d.node("l", 400, 50, 200, 90, "green", "Leader", ["admits runs", "renews the lease"], tag="ACTIVE")
    d.node("s", 400, 250, 200, 90, "amber", "Standby", ["ready to take over"], tag="STANDBY")
    d.node("db", 750, 135, 180, 122, "violet", "PostgreSQL", ["lease row"], shape="cylinder")
    d.edge("c", "l", "green", fa=0.25)
    d.edge("c", "s", "rose", kind="ctl", fa=0.75, label="503", label_t=0.55)
    d.edge("l", "db", "green", fb=0.3, label="renews")
    d.edge("s", "db", "amber", kind="ctl", fb=0.7, label="waits for expiry", label_dy=2)
    d.save()


if __name__ == "__main__":
    for fn in (workflow_anatomy, status_model, architecture, backpressure, agent_loop, scheduling,
               module_map, feature_map, remote_workers, high_availability):
        fn()
