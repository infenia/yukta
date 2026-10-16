# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Generates the docs diagrams as animated SVGs that look like Yukta's own workflow canvas.

Run from anywhere:  python3 docs/images/diagrams/generate.py

Every diagram is written twice, `<name>.light.svg` and `<name>.dark.svg`, from the product's
design tokens (ui/src/index.css). The pages show one or the other with Mintlify's
`block dark:hidden` / `hidden dark:block` image classes.

The drawing follows the web UI on purpose:
  * nodes copy WorkflowNodeCard: a surface card, 1px border, 3px top border and an icon tile in
    the category colour (trigger orange, processor blue, terminal slate);
  * the canvas is the xyflow background: page colour with a dot grid;
  * edges are 1.5px outline strokes; command and event edges are dashed;
  * motion is a "run": nodes light up one after another with the canvas's running breathe while a
    blue pulse travels the edge to the next node. Under prefers-reduced-motion it is a still frame.
Colour carries meaning only: orange marks where a flow starts (and the live state), blue is the
engine doing work, slate is where work ends or is stored, red is an error path.
"""

import html
import pathlib

OUT = pathlib.Path(__file__).resolve().parent

# Values from ui/src/index.css (oklch, converted to sRGB hex).
THEMES = {
    "light": {
        "bg": "#FBFCFE",
        "card": "#FFFFFF",
        "fg": "#11161F",
        "muted_fg": "#545E6F",
        "border": "#DEE1E8",
        "outline": "#677895",
        "primary": "#376EF4",
        "danger": "#E7000B",
        "trigger": "#DD5400",
        "processor": "#376EF4",
        "terminal": "#677895",
        "tint_mix": 0.12,
        "shadow": "0 1px 2px rgba(17,22,31,.06)",
        "shadow_opacity": 0.10,
        "grid_opacity": 1.0,
    },
    "dark": {
        "bg": "#0A0D14",
        "card": "#11161F",
        "fg": "#F3F5F9",
        "muted_fg": "#9AA5B8",
        "border": "#2D333D",
        "outline": "#7F90AE",
        "primary": "#6FA2FF",
        "danger": "#FF6467",
        "trigger": "#FC8C45",
        "processor": "#6FA2FF",
        "terminal": "#8E9FBE",
        "tint_mix": 0.16,
        "shadow_opacity": 0.45,
        "grid_opacity": 0.9,
    },
}

SANS = "Inter, 'Inter Variable', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif"
MONO = "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

# 16x16 line icons drawn in the category colour inside the node's icon tile.
ICONS = {
    "bolt": '<path d="M9.2 1.8 3.6 9h4.2l-1 5.2L12.4 7H8.2z" fill="currentColor" stroke="none"/>',
    "globe": '<circle cx="8" cy="8" r="6"/><path d="M2 8h12M8 2c2 2.2 2 9.8 0 12M8 2c-2 2.2-2 9.8 0 12"/>',
    "shuffle": '<path d="M2 4.5h3l6 7h3M2 11.5h3l1.6-1.9M9.4 6.4 11 4.5h3M12 2.8l2 1.7-2 1.7M12 9.8l2 1.7-2 1.7"/>',
    "send": '<path d="M2.2 7.6 13.8 2.4 9.6 13.6 7.6 8.6z M7.6 8.6l6.2-6.2"/>',
    "inbox": '<path d="M2 9.5 4 3h8l2 6.5V13H2z M2 9.5h3.5l1 1.6h3l1-1.6H14"/>',
    "clock": '<circle cx="8" cy="8" r="6"/><path d="M8 4.6V8l2.4 1.6"/>',
    "queue": '<rect x="2" y="3" width="12" height="2.6" rx="1"/><rect x="2" y="6.8" width="12" height="2.6" rx="1"/><rect x="2" y="10.6" width="7" height="2.6" rx="1"/>',
    "play": '<path d="M5 3.2 12.4 8 5 12.8z" fill="currentColor" stroke="none"/>',
    "flag": '<path d="M3.5 14V2.5M3.5 3h8.5l-1.8 3 1.8 3H3.5"/>',
    "users": '<circle cx="6" cy="5.6" r="2.4"/><path d="M1.8 13.4c.6-2.6 2.2-3.8 4.2-3.8s3.6 1.2 4.2 3.8"/><path d="M10.6 3.4a2.3 2.3 0 0 1 0 4.4M12 9.9c1.1.6 1.9 1.7 2.2 3.5"/>',
    "gate": '<path d="M2.5 14V5.5L8 2l5.5 3.5V14M5.5 14V8.5h5V14"/>',
    "code": '<path d="M5.5 4.5 2 8l3.5 3.5M10.5 4.5 14 8l-3.5 3.5M9.2 3 6.8 13"/>',
    "graph": '<circle cx="3.6" cy="8" r="1.8"/><circle cx="12.4" cy="3.8" r="1.8"/><circle cx="12.4" cy="12.2" r="1.8"/><path d="M5.3 7.2 10.7 4.6M5.3 8.8l5.4 2.6"/>',
    "puzzle": '<path d="M3 5h2.6a1.6 1.6 0 1 1 3 0H11v2.6a1.6 1.6 0 1 1 0 3V13H8.6a1.6 1.6 0 1 0-3 0H3z"/>',
    "pulse": '<path d="M1.5 8.5h3l1.6-4 2.8 8 1.8-4h3.8"/>',
    "db": '<ellipse cx="8" cy="3.8" rx="5" ry="1.9"/><path d="M3 3.8v8.4c0 1 2.2 1.9 5 1.9s5-.9 5-1.9V3.8M3 8c0 1 2.2 1.9 5 1.9S13 9 13 8"/>',
    "sliders": '<path d="M3 4h10M3 8h10M3 12h10"/><circle cx="6" cy="4" r="1.5" fill="currentColor"/><circle cx="10.5" cy="8" r="1.5" fill="currentColor"/><circle cx="5" cy="12" r="1.5" fill="currentColor"/>',
    "eye": '<path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/>',
    "list": '<path d="M6 4h8M6 8h8M6 12h8"/><circle cx="2.8" cy="4" r=".9" fill="currentColor"/><circle cx="2.8" cy="8" r=".9" fill="currentColor"/><circle cx="2.8" cy="12" r=".9" fill="currentColor"/>',
    "spark": '<path d="M8 1.8c.5 3.4 1.8 4.7 5.2 5.2-3.4.5-4.7 1.8-5.2 5.2-.5-3.4-1.8-4.7-5.2-5.2 3.4-.5 4.7-1.8 5.2-5.2z"/><path d="M13 11.2v3M11.5 12.7h3"/>',
    "branch": '<circle cx="4" cy="3.5" r="1.6"/><circle cx="4" cy="12.5" r="1.6"/><circle cx="12" cy="6" r="1.6"/><path d="M4 5.1v5.8M12 7.6c0 2.6-3 2.4-6.6 4"/>',
    "wrench": '<path d="M10.2 2.2a3.4 3.4 0 0 0-3.8 4.6L2.2 11a1.4 1.4 0 0 0 2 2l4.2-4.2a3.4 3.4 0 0 0 4.6-3.8l-2 2-1.9-.4-.4-1.9z"/>',
    "check": '<circle cx="8" cy="8" r="6"/><path d="M5.2 8.2 7.2 10.2 10.8 6"/>',
    "repeat": '<path d="M2.5 7V6a2 2 0 0 1 2-2h8.5M11 2l2 2-2 2M13.5 9v1a2 2 0 0 1-2 2H3M5 14l-2-2 2-2"/>',
    "layers": '<path d="M8 2 14 5 8 8 2 5z M2 8l6 3 6-3 M2 11l6 3 6-3"/>',
    "server": '<rect x="2.2" y="2.5" width="11.6" height="4.4" rx="1.2"/><rect x="2.2" y="9.1" width="11.6" height="4.4" rx="1.2"/><circle cx="5" cy="4.7" r=".7" fill="currentColor"/><circle cx="5" cy="11.3" r=".7" fill="currentColor"/>',
    "box": '<path d="M8 1.8 13.6 5v6L8 14.2 2.4 11V5z M2.4 5 8 8.2 13.6 5 M8 8.2v6"/>',
    "plug": '<path d="M5.5 2v3.5M10.5 2v3.5M3.5 5.5h9V8a4.5 4.5 0 0 1-9 0z M8 12.5V14.5"/>',
    "shield": '<path d="M8 1.8 13.2 3.8v4c0 3.2-2.2 5.3-5.2 6.4-3-1.1-5.2-3.2-5.2-6.4v-4z"/><path d="M5.8 8.2 7.4 9.8 10.4 6.6"/>',
    "child": '<rect x="2" y="2" width="6" height="4.4" rx="1"/><rect x="8" y="9.6" width="6" height="4.4" rx="1"/><path d="M5 6.4v5.4h3"/>',
    "terminal": '<rect x="1.8" y="2.8" width="12.4" height="10.4" rx="1.6"/><path d="M4.6 6.2 6.8 8l-2.2 1.8M8.4 10h3"/>',
}

ROLE = {"trigger", "processor", "terminal"}


def esc(text):
    return html.escape(str(text), quote=True)


def mix(c1, c2, t):
    """sRGB mix: t of c1 over c2."""
    a = [int(c1[i : i + 2], 16) for i in (1, 3, 5)]
    b = [int(c2[i : i + 2], 16) for i in (1, 3, 5)]
    return "#%02X%02X%02X" % tuple(round(x * t + y * (1 - t)) for x, y in zip(a, b))


def text_width(text, size, mono=False, weight=400):
    """Rough advance width, deliberately generous so text never touches a node edge."""
    per = 0.62 if mono else (0.56 if weight >= 600 else 0.53)
    return len(text) * size * per


class Diagram:
    def __init__(self, name, width, height, alt, step_s=0.9):
        self.name = name
        self.w = width
        self.h = height
        self.alt = alt
        self.step_s = step_s
        self.nodes = {}
        self.order = []
        self.edges = []
        self.legend_kind = None

    # ------------------------------------------------------------------ model

    def node(self, nid, x, y, w, h, role, title, sub=(), icon="graph", mono=False, step=None,
             live=False):
        assert role in ROLE, role
        self.nodes[nid] = dict(id=nid, x=x, y=y, w=w, h=h, role=role, title=title, sub=list(sub),
                               icon=icon, mono=mono, step=step, live=live, idx=len(self.order))
        self.order.append(nid)

    def edge(self, a, b, sa="r", sb="l", fa=0.5, fb=0.5, kind="data", label=None, label_t=0.5,
             label_dx=0, label_dy=0, bow=None, tone=None):
        self.edges.append(dict(a=a, b=b, sa=sa, sb=sb, fa=fa, fb=fb, kind=kind, label=label,
                               label_t=label_t, label_dx=label_dx, label_dy=label_dy, bow=bow,
                               tone=tone, idx=len(self.edges)))

    def legend(self, kind):
        """'lines' explains solid vs dashed edges; 'roles' explains the three node colours."""
        self.legend_kind = kind

    # ------------------------------------------------------------------ geometry

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

    def geometry(self, e):
        x1, y1, d1 = self.anchor(self.nodes[e["a"]], e["sa"], e["fa"])
        x2, y2, d2 = self.anchor(self.nodes[e["b"]], e["sb"], e["fb"])
        if e["bow"] is not None:
            k = e["bow"]
        else:
            span = abs(x2 - x1) if d1[0] else abs(y2 - y1)
            k = max(30.0, span * 0.5)
        c1 = (x1 + d1[0] * k, y1 + d1[1] * k)
        c2 = (x2 + d2[0] * k, y2 + d2[1] * k)
        d = (f"M{x1:.1f},{y1:.1f} C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} "
             f"{x2:.1f},{y2:.1f}")
        return d, bezier((x1, y1), c1, c2, (x2, y2), e["label_t"])

    # ------------------------------------------------------------------ timing

    def steps(self):
        return max([n["step"] for n in self.nodes.values() if n["step"] is not None], default=0)

    def cycle(self):
        # One beat per step, plus a rest so the loop has a visible pause before it repeats.
        return (self.steps() + 2.2) * self.step_s

    def css(self, t):
        cyc = self.cycle()
        s = self.step_s
        on = 100 * (s * 1.25) / cyc
        fade = 100 * (s * 1.7) / cyc
        travel = 100 * (s * 0.95) / cyc
        return f"""
.graph{{animation:graph-in .45s ease-out both}}
@keyframes graph-in{{from{{opacity:0}}}}
.run{{opacity:0;animation:run {cyc:.2f}s ease-in-out infinite;animation-delay:var(--at)}}
@keyframes run{{0%{{opacity:0}}{on * .35:.2f}%{{opacity:1}}{on:.2f}%{{opacity:1}}{fade:.2f}%,100%{{opacity:0}}}}
.live{{animation:breathe 2.4s ease-in-out infinite}}
@keyframes breathe{{0%,100%{{opacity:.35}}50%{{opacity:1}}}}
.pulse{{stroke-dasharray:14 400;stroke-dashoffset:14;opacity:0;
  animation:pulse {cyc:.2f}s cubic-bezier(.45,.05,.55,.95) infinite;animation-delay:var(--at)}}
@keyframes pulse{{0%{{stroke-dashoffset:14;opacity:0}}2%{{opacity:1}}
  {travel:.2f}%{{stroke-dashoffset:-100;opacity:1}}{travel + .01:.2f}%,100%{{stroke-dashoffset:-100;opacity:0}}}}
.march{{animation:march 1.6s linear infinite}}
@keyframes march{{to{{stroke-dashoffset:-16}}}}
@media (prefers-reduced-motion:reduce){{
  .graph,.run,.live,.pulse,.march{{animation:none}}
  .run,.pulse{{opacity:0}}
  .live{{opacity:.7}}
}}
"""

    # ------------------------------------------------------------------ rendering

    def accent(self, t, role):
        return t[role]

    def render_defs(self, t):
        out = ["<defs>"]
        out.append(
            f'<pattern id="dots" width="18" height="18" patternUnits="userSpaceOnUse">'
            f'<circle cx="1" cy="1" r="1" fill="{t["border"]}" '
            f'opacity="{t["grid_opacity"]}"/></pattern>'
        )
        out.append(f'<clipPath id="frame"><rect width="{self.w}" height="{self.h}" rx="14"/>'
                   "</clipPath>")
        out.append('<filter id="soft" x="-30%" y="-30%" width="160%" height="180%">'
                   '<feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#11161F" '
                   f'flood-opacity="{t["shadow_opacity"]}"/></filter>')
        out.append('<filter id="halo" x="-40%" y="-40%" width="180%" height="180%">'
                   '<feGaussianBlur stdDeviation="7"/></filter>')
        for name, color in (("outline", t["outline"]), ("primary", t["primary"]),
                            ("danger", t["danger"])):
            out.append(
                f'<marker id="arrow-{name}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" '
                f'markerHeight="8" markerUnits="userSpaceOnUse" orient="auto">'
                f'<path d="M1,1.4 L9,5 L1,8.6 z" fill="{color}"/></marker>'
            )
        out.append("</defs>")
        return "".join(out)

    def at(self, step, offset=0.0):
        return f"{step * self.step_s + offset:.2f}s"

    def render_node(self, n, t):
        x, y, w, h = n["x"], n["y"], n["w"], n["h"]
        acc = t[n["role"]]
        tint = mix(acc, t["card"], t["tint_mix"])
        g = []
        # Running state: the canvas's node-breathe (accent ring plus soft glow).
        if n["step"] is not None or n["live"]:
            cls = "live" if n["live"] else "run"
            style = "" if n["live"] else f' style="--at:{self.at(n["step"])}"'
            g.append(
                f'<g class="{cls}"{style}>'
                f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{acc}" '
                f'opacity=".38" filter="url(#halo)"/>'
                f'<rect x="{x - 1.5}" y="{y - 1.5}" width="{w + 3}" height="{h + 3}" rx="13.5" '
                f'fill="none" stroke="{acc}" stroke-width="1.5"/></g>'
            )
        # Card: 1px border, 3px top border in the category colour (WorkflowNodeCard).
        g.append(f'<g filter="url(#soft)">'
                 f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{acc}"/>'
                 f'<rect x="{x}" y="{y + 3}" width="{w}" height="{h - 3}" rx="12" '
                 f'fill="{t["border"]}"/>'
                 f'<rect x="{x + 1}" y="{y + 3}" width="{w - 2}" height="{h - 4}" rx="11" '
                 f'fill="{t["card"]}"/></g>')
        # Icon tile.
        tile = 32
        tx = x + 12
        ty = y + 3 + (h - 3 - tile) / 2
        g.append(f'<rect x="{tx}" y="{ty:.1f}" width="{tile}" height="{tile}" rx="8" '
                 f'fill="{tint}"/>')
        g.append(f'<g transform="translate({tx + 8} {ty + 8:.1f})" color="{acc}" fill="none" '
                 f'stroke="{acc}" stroke-width="1.5" stroke-linecap="round" '
                 f'stroke-linejoin="round">{ICONS[n["icon"]]}</g>')
        # Text block, left aligned next to the tile, vertically centred.
        lx = tx + tile + 11
        title_size = 14 if n["mono"] else 15
        lines = [("title", n["title"], title_size, 20)] + [("sub", s, 13, 17) for s in n["sub"]]
        total = sum(lh for *_, lh in lines)
        cy = y + 3 + (h - 3) / 2 - total / 2
        room = x + w - 12 - lx
        for kind, text, size, lh in lines:
            base = cy + lh * 0.74
            cy += lh
            mono = n["mono"] and kind == "title"
            weight = 600 if kind == "title" else 400
            need = text_width(text, size, mono, weight)
            if need > room:
                print(f"  warn {self.name}/{n['id']}: '{text}' needs {need:.0f}px, has {room:.0f}")
            fam = MONO if mono else SANS
            color = t["fg"] if kind == "title" else t["muted_fg"]
            g.append(f'<text x="{lx}" y="{base:.1f}" font-family="{fam}" font-size="{size}" '
                     f'font-weight="{weight}" fill="{color}">{esc(text)}</text>')
        return "".join(g)

    def render_edge(self, e, t):
        d, mid = self.geometry(e)
        tone = e["tone"]
        stroke = t["danger"] if tone == "danger" else t["outline"]
        marker = "danger" if tone == "danger" else "outline"
        ctl = e["kind"] == "ctl"
        dash = ' stroke-dasharray="5 5" class="march"' if ctl else ""
        parts = [f'<path d="{d}" fill="none" stroke="{stroke}" stroke-width="1.5" '
                 f'stroke-opacity="{0.9 if tone else 0.75}" stroke-linecap="round"{dash} '
                 f'marker-end="url(#arrow-{marker})"/>']
        src = self.nodes[e["a"]]
        if src["step"] is not None and not ctl:
            pulse = t["danger"] if tone == "danger" else t["primary"]
            parts.append(
                f'<path class="pulse" style="--at:{self.at(src["step"], self.step_s * 0.45)}" '
                f'd="{d}" pathLength="100" fill="none" stroke="{pulse}" stroke-width="3" '
                'stroke-linecap="round"/>'
            )
        return "".join(parts), mid

    def render_label(self, e, mid, t):
        text = e["label"]
        w = text_width(text, 13, weight=500) + 20
        x = mid[0] + e["label_dx"]
        y = mid[1] + e["label_dy"]
        color = t["danger"] if e["tone"] == "danger" else t["muted_fg"]
        border = mix(t["danger"], t["bg"], 0.35) if e["tone"] == "danger" else t["border"]
        return (f'<rect x="{x - w / 2:.1f}" y="{y - 12:.1f}" width="{w:.1f}" height="24" rx="12" '
                f'fill="{t["bg"]}" stroke="{border}"/>'
                f'<text x="{x:.1f}" y="{y + 4.6:.1f}" text-anchor="middle" font-family="{SANS}" '
                f'font-size="13" font-weight="500" fill="{color}">{esc(text)}</text>')

    def render_legend(self, t):
        x, y = 22, self.h - 22
        out = []
        if self.legend_kind == "lines":
            out.append(f'<path d="M{x},{y} H{x + 26}" stroke="{t["outline"]}" stroke-width="1.5"/>')
            out.append(self.legend_text(x + 34, y, "data", t))
            out.append(f'<path d="M{x + 96},{y} H{x + 122}" stroke="{t["outline"]}" '
                       'stroke-width="1.5" stroke-dasharray="5 5"/>')
            out.append(self.legend_text(x + 132, y, "commands and events", t))
        elif self.legend_kind == "roles":
            cx = x
            for role, label in (("trigger", "Trigger"), ("processor", "Processor"),
                                ("terminal", "Terminal")):
                out.append(f'<rect x="{cx}" y="{y - 5}" width="10" height="10" rx="3" '
                           f'fill="{t[role]}"/>')
                out.append(self.legend_text(cx + 16, y, label, t))
                cx += 16 + text_width(label, 13) + 26
        return "".join(out)

    @staticmethod
    def legend_text(x, y, text, t):
        return (f'<text x="{x}" y="{y + 4.5}" font-family="{SANS}" font-size="13" '
                f'fill="{t["muted_fg"]}">{esc(text)}</text>')

    def render(self, theme):
        t = THEMES[theme]
        w, h = self.w, self.h
        s = [
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" '
            f'height="{h}" role="img" aria-label="{esc(self.alt)}">',
            f"<title>{esc(self.alt)}</title>",
            f"<style>{self.css(t)}</style>",
            self.render_defs(t),
            f'<g clip-path="url(#frame)"><rect width="{w}" height="{h}" fill="{t["bg"]}"/>'
            f'<rect width="{w}" height="{h}" fill="url(#dots)"/></g>',
            '<g class="graph">',
        ]
        labels = []
        for e in self.edges:
            svg, mid = self.render_edge(e, t)
            s.append(svg)
            if e["label"]:
                labels.append(self.render_label(e, mid, t))
        for nid in self.order:
            s.append(self.render_node(self.nodes[nid], t))
        s.extend(labels)
        s.append(self.render_legend(t))
        s.append("</g>")
        s.append(f'<rect x=".5" y=".5" width="{w - 1}" height="{h - 1}" rx="13.5" fill="none" '
                 f'stroke="{t["border"]}"/>')
        s.append("</svg>")
        return "\n".join(s) + "\n"

    def save(self):
        for theme in THEMES:
            path = OUT / f"{self.name}.{theme}.svg"
            path.write_text(self.render(theme), encoding="utf-8")
        print("wrote", self.name, "(light, dark)")


def bezier(p0, p1, p2, p3, t):
    u = 1 - t
    return (
        u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
        u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
    )


# ===================================================================== diagrams
# Roles: trigger = where a flow starts (orange), processor = the engine at work (blue),
# terminal = where work ends or is kept (slate).


def workflow_anatomy():
    d = Diagram("workflow-anatomy", 1010, 330,
                "A trigger starts the flow, a processor calls an API, a second processor "
                "transforms the result and a terminal notifies. A failed call goes to a "
                "dead-letter terminal.")
    d.node("t", 30, 60, 200, 76, "trigger", "Trigger", ["starts the flow"], "bolt", step=0)
    d.node("p1", 290, 60, 200, 76, "processor", "Processor", ["calls an API"], "globe", step=1)
    d.node("p2", 550, 60, 200, 76, "processor", "Processor", ["transforms"], "shuffle", step=2)
    d.node("e", 810, 60, 170, 76, "terminal", "Terminal", ["notifies"], "send", step=3)
    d.node("d", 290, 200, 200, 76, "terminal", "Terminal", ["dead letter"], "inbox")
    d.edge("t", "p1")
    d.edge("p1", "p2", label="success")
    d.edge("p1", "d", sa="b", sb="t", label="error", tone="danger")
    d.edge("p2", "e")
    d.legend("roles")
    d.save()


def status_model():
    d = Diagram("status-model", 1010, 200,
                "Run and node status only move forward: PENDING, QUEUED, RUNNING, then a "
                "terminal status.")
    d.node("p", 30, 50, 200, 76, "terminal", "PENDING", ["created, not run"], "clock",
           mono=True, step=0)
    d.node("q", 290, 50, 200, 76, "terminal", "QUEUED", ["waiting for a slot"], "queue",
           mono=True, step=1)
    d.node("r", 550, 50, 200, 76, "trigger", "RUNNING", ["first input arrived"], "play",
           mono=True, live=True)
    d.node("t", 810, 50, 170, 76, "terminal", "Final status", ["never reopens"], "check")
    d.edge("p", "q")
    d.edge("q", "r")
    d.edge("r", "t")
    d.save()


def architecture():
    d = Diagram("architecture", 960, 520,
                "Clients enter through admission and definition into the stream engine, which "
                "runs plugins and feeds observation and durability. A control plane sends ordered "
                "commands to the engine, and supervisors read the event feed to issue commands.",
                step_s=0.8)
    cw, h = 250, 84
    c1, c2, c3 = 30, 355, 680
    d.node("cl", c1, 36, cw, h, "trigger", "Clients", ["REST · web UI · MCP · CLI"], "users",
           step=0)
    d.node("en", c2, 36, cw, h, "processor", "Entry and admission",
           ["limits, queue, idempotency"], "gate", step=1)
    d.node("df", c3, 36, cw, h, "processor", "Definition", ["validates and prepares"], "code",
           step=2)
    d.node("su", c1, 204, cw, h, "processor", "Supervisors", ["rules, a model or code"], "eye")
    d.node("cp", c2, 204, cw, h, "processor", "Control plane", ["ordered, audited commands"],
           "sliders")
    d.node("sg", c3, 204, cw, h, "processor", "Stream engine", ["a stream per edge"], "graph",
           step=3)
    d.node("ob", c1, 372, cw, h, "terminal", "Observation", ["progress, logs, metrics"], "pulse",
           step=4)
    d.node("du", c2, 372, cw, h, "terminal", "Durability", ["execution log, checkpoints"], "db",
           step=4)
    d.node("pl", c3, 372, cw, h, "terminal", "Plugins", ["local or remote workers"], "puzzle",
           step=4)
    d.edge("cl", "en")
    d.edge("en", "df")
    d.edge("df", "sg", sa="b", sb="t")
    d.edge("sg", "pl", sa="b", sb="t", fa=0.82, fb=0.82)
    d.edge("sg", "du", sa="b", sb="t", fa=0.5, fb=0.5)
    d.edge("sg", "ob", sa="b", sb="t", fa=0.16, fb=0.72, bow=40)
    d.edge("su", "cp", kind="ctl", label="commands")
    d.edge("cp", "sg", kind="ctl", label="commands")
    d.edge("ob", "su", sa="t", sb="b", fa=0.28, fb=0.28, kind="ctl", label="event feed")
    d.legend("lines")
    d.save()


def backpressure():
    d = Diagram("backpressure", 960, 380,
                "A trigger feeds a fast node, which feeds a slow node and another fast node. The "
                "slow node paces the fast node and the trigger.")
    d.node("t", 30, 190, 200, 76, "trigger", "Trigger", ["emits messages"], "bolt", step=0)
    d.node("a", 300, 190, 200, 76, "processor", "Fast node", ["waits when full"], "shuffle",
           step=1)
    d.node("b", 640, 100, 210, 76, "processor", "Slow node", ["takes its time"], "clock", step=2)
    d.node("c", 640, 270, 210, 76, "processor", "Fast node", ["keeps up"], "shuffle", step=2)
    d.edge("t", "a")
    d.edge("a", "b")
    d.edge("a", "c")
    d.edge("b", "a", sa="t", sb="t", kind="ctl", bow=78,
           label="slow node paces A and the trigger", label_dy=-4)
    d.save()


def agent_loop():
    d = Diagram("agent-loop", 1070, 330,
                "An agent workflow lists MCP tools, asks the model, branches on whether it asked "
                "for a tool, maps the arguments, calls the tool and asks the model for a final "
                "answer.", step_s=0.75)
    w, h = 194, 76
    xs = [20, 228, 436, 644, 852]
    top, bot = 50, 210
    d.node("l", xs[0], top, w, h, "processor", "MCP_LIST_TOOLS", ["finds tools"], "list",
           mono=True, step=0)
    d.node("p", xs[1], top, w, h, "processor", "LLM_CALL", ["picks a tool"], "spark", mono=True,
           step=1)
    d.node("b", xs[2], top, w, h, "processor", "BRANCH", ["asked for a tool?"], "branch",
           mono=True, step=2)
    d.node("m", xs[3], top, w, h, "processor", "MAPPER", ["builds arguments"], "shuffle",
           mono=True, step=3)
    d.node("a", xs[4], top, w, h, "processor", "MCP_TOOL_CALL", ["runs the tool"], "wrench",
           mono=True, step=4)
    d.node("f", xs[4], bot, w, h, "processor", "LLM_CALL", ["final answer"], "spark", mono=True,
           step=5)
    d.node("e", xs[3], bot, w, h, "terminal", "Terminal", ["answer"], "flag", step=6)
    d.node("dd", xs[2], bot, w, h, "terminal", "Terminal", ["no tool needed"], "flag")
    d.edge("l", "p")
    d.edge("p", "b")
    d.edge("b", "m", label="yes")
    d.edge("m", "a")
    d.edge("a", "f", sa="b", sb="t")
    d.edge("f", "e", sa="l", sb="r")
    d.edge("b", "dd", sa="b", sb="t", label="no")
    d.save()


def scheduling():
    d = Diagram("scheduling", 980, 300,
                "A workflow is started once and holds one open execution. SCHEDULE_TRIGGER emits "
                "a tick into it, and the nodes handle every tick as a message.")
    d.node("s", 30, 44, 250, 76, "terminal", "Start once", ["one REST, CLI or MCP call"],
           "terminal")
    d.node("t", 30, 180, 250, 76, "trigger", "SCHEDULE_TRIGGER", ["tick 1, 2, 3 ..."], "clock",
           mono=True, step=0)
    d.node("x", 380, 112, 230, 76, "processor", "One open execution", ["holds one slot"], "repeat",
           step=1)
    d.node("g", 720, 112, 230, 76, "processor", "Nodes", ["handle each tick"], "graph", step=2)
    d.edge("s", "x", fb=0.3, kind="ctl")
    d.edge("t", "x", fb=0.7)
    d.edge("x", "g")
    d.save()


def module_map():
    d = Diagram("module-map", 1080, 480,
                "Clients reach the web, mcp and ui modules, which call core. Core uses plugin-api "
                "and plugins, messaging, persistence-postgres and workers. The boot module wires "
                "everything into one Spring Boot app.", step_s=0.8)
    d.node("boot", 300, 30, 250, 72, "terminal", "boot", ["one Spring Boot app"], "box",
           mono=True)
    d.node("cl", 24, 196, 220, 76, "trigger", "Clients", ["REST, web UI, MCP, CLI"], "users",
           step=0)
    d.node("ed", 300, 196, 190, 76, "processor", "web, mcp, ui", ["edge modules"], "globe",
           mono=True, step=1)
    d.node("co", 546, 196, 216, 76, "processor", "core", ["engine, control plane"], "graph",
           mono=True, step=2)
    d.node("pl", 806, 30, 250, 70, "terminal", "plugin-api", ["and plugins"], "puzzle",
           mono=True, step=3)
    d.node("ms", 806, 136, 250, 70, "terminal", "messaging", ["shared abstractions"], "send",
           mono=True, step=3)
    d.node("st", 806, 242, 250, 70, "terminal", "persistence-postgres", ["stores"], "db",
           mono=True, step=3)
    d.node("wk", 806, 348, 250, 70, "terminal", "workers", ["remote node work"], "server",
           mono=True, step=3)
    d.edge("cl", "ed")
    d.edge("ed", "co")
    d.edge("co", "pl", fa=0.15)
    d.edge("co", "ms", fa=0.38)
    d.edge("co", "st", fa=0.62)
    d.edge("co", "wk", fa=0.85)
    d.edge("pl", "ms", sa="b", sb="t", fa=0.85, fb=0.85, bow=18)
    d.edge("boot", "ed", sa="b", sb="t", fa=0.3, kind="ctl", label="wires together",
           label_t=0.5, label_dx=-48)
    d.edge("boot", "co", sa="b", sb="t", fa=0.75, kind="ctl")
    d.save()


def feature_map():
    d = Diagram("feature-map", 1120, 560,
                "Feature groups of the core: entry and admission load a definition, the stream "
                "engine runs plugins and sub-workflows and emits events to observation, which logs "
                "to durability and persistence. Supervisors send commands through the control "
                "plane to the engine.", step_s=0.75)
    cw, h = 236, 80
    cx = [20, 302, 584, 866]
    r1, r2, r3 = 36, 210, 384
    d.node("adm", cx[0], r1, cw, h, "trigger", "Entry and Admission", ["ADM · slots, queue"],
           "gate", step=0)
    d.node("def", cx[1], r1, cw, h, "processor", "Definition", ["DEF · validate, compile"],
           "code", step=1)
    d.node("eng", cx[2], r1, cw, h, "processor", "Stream Engine", ["ENG · nodes, policies"],
           "graph", step=2)
    d.node("ext", cx[3], r1, cw, h, "terminal", "Plugins / Workers", ["EXT · SCL · node work"],
           "puzzle", step=3)
    d.node("obs", cx[1], r2, cw, h, "terminal", "Observation", ["OBS · tracker, metrics"],
           "pulse", step=3)
    d.node("ctl", cx[2], r2, cw, h, "processor", "Control Plane", ["CTL · ordered, audited"],
           "sliders")
    d.node("sub", cx[3], r2, cw, h, "processor", "Sub-workflows", ["ENG-07 · child runs"],
           "child", step=3)
    d.node("per", cx[0], r3, cw, h, "terminal", "Persistence", ["files · PostgreSQL"], "db",
           step=5)
    d.node("dur", cx[1], r3, cw, h, "terminal", "Durability", ["DUR · log, checkpoints"],
           "layers", step=4)
    d.node("sup", cx[2], r3, cw, h, "processor", "Supervisors", ["CTL-07 · rules, AI, code"],
           "eye")
    d.node("ha", cx[3], r3, cw, h, "terminal", "HA and Security", ["SCL-02 · SEC · keys"],
           "shield")
    d.edge("adm", "def", label="load")
    d.edge("def", "eng", label="graph")
    d.edge("eng", "ext", label="stream")
    d.edge("eng", "sub", sa="b", sb="t", fa=0.88, bow=40, label="child run", label_dx=24,
           label_dy=8)
    d.edge("eng", "obs", sa="b", sb="t", fa=0.12, bow=40, label="events", label_dx=-24,
           label_dy=8)
    d.edge("obs", "dur", sa="b", sb="t", label="log", label_dx=22)
    d.edge("dur", "per", sa="l", sb="r", label="store")
    d.edge("sup", "ctl", sa="t", sb="b", kind="ctl", label="cmds", label_dx=26)
    d.edge("ctl", "eng", sa="t", sb="b", kind="ctl", label="commands", label_dx=36)
    d.edge("obs", "ctl", kind="ctl", label="feed")
    d.legend("lines")
    d.save()


def remote_workers():
    d = Diagram("remote-workers", 960, 330,
                "The control plane opens one channel per attempt to each worker. Workers register "
                "and renew their lease with the control plane.")
    d.node("cp", 30, 80, 240, 170, "processor", "Control plane", ["admits and tracks runs"],
           "sliders", step=0)
    d.node("w1", 690, 40, 240, 76, "terminal", "Worker A", ["pool default"], "server", step=1)
    d.node("w2", 690, 214, 240, 76, "terminal", "Worker B", ["pool default"], "server", step=1)
    d.edge("cp", "w1", fa=0.15, fb=0.25, label="one channel per attempt", label_dy=-14)
    d.edge("w1", "cp", sa="l", sb="r", fa=0.8, fb=0.36, kind="ctl",
           label="register, renew lease", label_dy=16)
    d.edge("cp", "w2", fa=0.85, fb=0.75)
    d.edge("w2", "cp", sa="l", sb="r", fa=0.25, fb=0.64, kind="ctl")
    d.save()


def high_availability():
    d = Diagram("high-availability", 960, 340,
                "Clients talk to the leader. A standby answers 503. Both servers use one "
                "PostgreSQL lease row: the leader renews it and the standby waits for it to "
                "expire.")
    d.node("c", 30, 132, 200, 76, "trigger", "Clients", ["REST, UI, agents"], "users", step=0)
    d.node("l", 380, 40, 220, 76, "processor", "Leader", ["admits runs"], "check", step=1)
    d.node("s", 380, 224, 220, 76, "processor", "Standby", ["ready to take over"], "clock")
    d.node("db", 730, 132, 200, 76, "terminal", "PostgreSQL", ["lease row"], "db", step=2)
    d.edge("c", "l", fa=0.25)
    d.edge("c", "s", fa=0.75, kind="ctl", tone="danger", label="503", label_t=0.55)
    d.edge("l", "db", fb=0.3, label="renews")
    d.edge("s", "db", fb=0.7, kind="ctl", label="waits for expiry")
    d.save()


if __name__ == "__main__":
    for old in OUT.glob("*.svg"):
        old.unlink()
    for fn in (workflow_anatomy, status_model, architecture, backpressure, agent_loop, scheduling,
               module_map, feature_map, remote_workers, high_availability):
        fn()
