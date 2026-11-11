# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Shared drawing kit for the concept illustrations (the home-page paths and the per-page diagrams).

A `Scene` is a generate.Diagram (nodes, edges, pulses, motion) that also lets you draw free-form
shapes UNDER the nodes (`under(t)`) and OVER them (`over(t)`), where `t` is the theme token dict.
Helpers return SVG strings: icon(), text(), panel(), chip(). Use `Scene(name, alt, under, over,
w=..., h=...)`, add d.node()/d.edge() as in generate.py, return it, and call .save().

Design rules (the existing diagrams follow them):
  * one idea per diagram, at most about 7 elements, labels of one to three words;
  * colour carries meaning: orange = where something starts or is live, blue = the engine or a
    step doing work, slate = where work ends, is stored or is a control/outside actor, red = an
    error path; dashed lines = commands and events, solid = data;
  * the canvas looks like the product: dot grid, node cards with a 3px category top border;
  * readable at ~390px wide: keep the viewBox near 480x270 and fonts at 14px or more;
  * always give an alt text that states the point, not just the parts.
"""

import generate as g
from generate import ICONS, MONO, SANS, THEMES, esc, mix

W, H = 480, 270  # default canvas, 16:9


class Scene(g.Diagram):
    """A Diagram that also draws free-form decoration under and over its nodes."""

    def __init__(self, name, alt, under=None, over=None, w=W, h=H, step_s=0.8):
        super().__init__(name, w, h, alt, step_s=step_s)
        self.under = under or (lambda t: "")
        self.over = over or (lambda t: "")

    def render_legend(self, t):  # called after the nodes: the "over" layer
        return self.over(t)

    def render(self, theme):
        svg = super().render(theme)
        return svg.replace('<g class="graph">', '<g class="graph">' + self.under(THEMES[theme]), 1)


def icon(name, x, y, color, size=16, scale=1.0, stroke=1.5):
    return (f'<g transform="translate({x} {y}) scale({scale})" color="{color}" fill="none" '
            f'stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" '
            f'stroke-linejoin="round">{ICONS[name]}</g>')


def text(x, y, s, t, size=18, weight=600, fill=None, anchor="start", mono=False):
    fam = MONO if mono else SANS
    return (f'<text x="{x}" y="{y}" font-family="{fam}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill or t["fg"]}" text-anchor="{anchor}">{esc(s)}</text>')


def panel(x, y, w, h, t, r=12, fill=None, stroke=None):
    return (f'<g filter="url(#soft)"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" '
            f'fill="{fill or t["card"]}" stroke="{stroke or t["border"]}"/></g>')


def chip(x, y, label, t, color, size=14, pad=9):
    w = g.text_width(label, size, mono=True) + pad * 2
    tint = mix(color, t["card"], t["tint_mix"] * 1.4)
    return (f'<rect x="{x}" y="{y}" width="{w:.1f}" height="22" rx="11" fill="{tint}" '
            f'stroke="{mix(color, t["card"], 0.45)}"/>'
            + text(x + w / 2, y + 15.5, label, t, size=size, weight=500, fill=color,
                   anchor="middle", mono=True)), w
