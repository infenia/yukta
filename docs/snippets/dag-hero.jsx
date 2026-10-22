// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * Animated example workflow for the docs home page. Pure inline SVG, no dependencies.
 * A ticker walks a run through ticket -> classify -> approve -> update -> done; every third loop
 * `classify` fails and the message takes the dashed error edge to `dead`. Static frame when the
 * user prefers reduced motion; paused while off-screen or in a background tab.
 */

export const DagHero = () => {
  // Mintlify evaluates only the exported component, so everything it needs is declared inside it.
  const LIB = useMemo(() => {
    const VB_W = 760;
    const VB_H = 260;
    const NODE_W = 104;
    const NODE_H = 44;
    const STEP = 154;

    const BLUE = "var(--yk-blue, #2f6bff)";
    const SLATE = "var(--yk-slate, #6a7a9b)";
    const ORANGE = "var(--yk-orange, #e85d0c)";
    const GREEN = "var(--yk-success, #008235)";
    const RED = "var(--yk-danger, #e7000b)";
    const SURFACE = "var(--yk-surface, #f0f4f9)";
    const BORDER = "var(--yk-border, #dee1e8)";
    const MONO = '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace';

    const KIND_COLOR = { trigger: ORANGE, processor: BLUE, terminal: SLATE };

    const NODES = [
      { id: "ticket", type: "API_TRIGGER", kind: "trigger", x: 20, y: 66 },
      { id: "classify", type: "LLM_CALL", kind: "processor", x: 20 + STEP, y: 66 },
      { id: "approve", type: "WAIT_FOR_SIGNAL", kind: "processor", x: 20 + 2 * STEP, y: 66 },
      { id: "update", type: "HTTP_REQUEST", kind: "processor", x: 20 + 3 * STEP, y: 66 },
      { id: "done", type: "CONSOLE_TERMINAL", kind: "terminal", x: 20 + 4 * STEP, y: 66 },
      { id: "dead", type: "CONSOLE_TERMINAL", kind: "terminal", x: 20 + STEP, y: 178, side: true },
    ];
    const NODE_BY_ID = {};
    NODES.forEach((n) => {
      NODE_BY_ID[n.id] = n;
    });

    const HAPPY = ["ticket", "classify", "approve", "update", "done"];

    // Edges: horizontal between neighbours on the happy path, vertical for classify -error-> dead.
    const EDGES = HAPPY.slice(0, -1)
      .map((id, i) => {
        const a = NODE_BY_ID[id];
        const b = NODE_BY_ID[HAPPY[i + 1]];
        const y = a.y + NODE_H / 2;
        return { id: id + "-" + b.id, x1: a.x + NODE_W, y1: y, x2: b.x, y2: y, err: false };
      })
      .concat([
        {
          id: "classify-dead",
          x1: NODE_BY_ID.classify.x + NODE_W / 2,
          y1: NODE_BY_ID.classify.y + NODE_H,
          x2: NODE_BY_ID.dead.x + NODE_W / 2,
          y2: NODE_BY_ID.dead.y,
          err: true,
        },
      ]);

    const NODE_MS = { ticket: 500, classify: 900, approve: 1100, update: 700, done: 600 };
    const EDGE_MS = 520;
    const PAUSE_MS = 1600;

    function buildSegments(fail) {
      const segs = [];
      const last = fail ? 1 : HAPPY.length - 1;
      for (let i = 0; i <= last; i++) {
        const id = HAPPY[i];
        segs.push({ t: "node", id, dur: fail && i === 1 ? NODE_MS[id] * 0.75 : NODE_MS[id] });
        if (i < last) {
          segs.push({ t: "edge", id: id + "-" + HAPPY[i + 1], dur: EDGE_MS });
        }
      }
      if (fail) {
        segs.push({ t: "node", id: "classify", dur: 500, fail: true });
        segs.push({ t: "edge", id: "classify-dead", dur: 650, err: true });
        segs.push({ t: "node", id: "dead", dur: 600 });
      }
      segs.push({ t: "pause", dur: PAUSE_MS });
      let total = 0;
      segs.forEach((s) => {
        total += s.dur;
      });
      return { segs, total };
    }

    const TIMELINES = [buildSegments(false), buildSegments(true)];
    // Every third loop is the failing one.
    const timelineFor = (loop) => TIMELINES[loop % 3 === 2 ? 1 : 0];

    function smooth(p) {
      return p * p * (3 - 2 * p);
    }

    // Pure: the full picture at time `t` (ms) into a loop.
    function frameAt(timeline, t) {
      const nodes = {};
      const edges = {};
      NODES.forEach((n) => {
        nodes[n.id] = "pending";
      });
      EDGES.forEach((e) => {
        edges[e.id] = "idle";
      });
      let dot = null;
      let start = 0;
      for (let i = 0; i < timeline.segs.length; i++) {
        const s = timeline.segs[i];
        const end = start + s.dur;
        if (t >= end) {
          if (s.t === "node") {
            nodes[s.id] = s.fail ? "failed" : "done";
          } else if (s.t === "edge") {
            edges[s.id] = "done";
          }
        } else {
          if (s.t === "node") {
            nodes[s.id] = s.fail ? "failed" : "running";
          } else if (s.t === "edge") {
            edges[s.id] = "active";
            dot = { id: s.id, p: smooth((t - start) / s.dur), err: !!s.err };
          }
          break;
        }
        start = end;
      }
      return { nodes, edges, dot, key: JSON.stringify([nodes, edges]) };
    }

    // What reduced-motion users (and the first paint) see: a run paused at the approval step.
    const STATIC_SNAP = {
      nodes: { ticket: "done", classify: "done", approve: "running", update: "pending", done: "pending", dead: "pending" },
      edges: {
        "ticket-classify": "done",
        "classify-approve": "done",
        "approve-update": "idle",
        "update-done": "idle",
        "classify-dead": "idle",
      },
    };

    function nodeStyle(status, id) {
      const base = { transition: "fill 250ms ease, stroke 250ms ease" };
      const tint = (c, pct) => "color-mix(in srgb, " + c + " " + pct + "%, " + SURFACE + ")";
      if (status === "running") {
        return { ...base, fill: tint(BLUE, 14), stroke: BLUE, strokeWidth: 2 };
      }
      if (status === "failed") {
        return { ...base, fill: tint(RED, 14), stroke: RED, strokeWidth: 2 };
      }
      if (status === "done") {
        const c = id === "dead" ? ORANGE : GREEN;
        return { ...base, fill: tint(c, 12), stroke: c, strokeWidth: 1.5 };
      }
      // Waiting nodes keep a visible border in their role colour; status only changes its colour.
      const role = KIND_COLOR[NODE_BY_ID[id].kind];
      return { ...base, fill: SURFACE, stroke: role, strokeWidth: 1.5, strokeOpacity: 0.75 };
    }

    function edgeStyle(status, err) {
      const base = { transition: "stroke 250ms ease", fill: "none", strokeLinecap: "round" };
      if (err) {
        return {
          ...base,
          stroke: ORANGE,
          strokeWidth: status === "active" ? 2.5 : 1.75,
          strokeOpacity: status === "idle" ? 0.7 : 1,
          strokeDasharray: "5 4",
        };
      }
      if (status === "active") {
        return { ...base, stroke: BLUE, strokeWidth: 2.5 };
      }
      if (status === "done") {
        return { ...base, stroke: GREEN, strokeWidth: 2, strokeOpacity: 0.9 };
      }
      return { ...base, stroke: SLATE, strokeWidth: 1.75, strokeOpacity: 0.6 };
    }

    function arrowPoints(e) {
      const pts =
        e.x1 === e.x2
          ? [[e.x2 - 4.5, e.y2 - 8], [e.x2, e.y2], [e.x2 + 4.5, e.y2 - 8]]
          : [[e.x2 - 8, e.y2 - 4.5], [e.x2, e.y2], [e.x2 - 8, e.y2 + 4.5]];
      return pts.map((p) => p[0] + "," + p[1]).join(" ");
    }

    function Tooltip({ node }) {
      const w = Math.round(node.type.length * 6.9 + 18);
      const h = 22;
      let x;
      let y;
      if (node.side) {
        x = node.x + NODE_W + 10;
        y = node.y + (NODE_H - h) / 2;
      } else {
        x = node.x + NODE_W / 2 - w / 2;
        y = node.y - h - 8;
      }
      x = Math.max(4, Math.min(VB_W - w - 4, x));
      return (
        <g pointerEvents="none">
          <rect x={x} y={y} width={w} height={h} rx={7} style={{ fill: SURFACE, stroke: BORDER, strokeWidth: 1 }} />
          <text x={x + w / 2} y={y + 15} textAnchor="middle" fontSize={11} fontFamily={MONO} fill="currentColor">
            {node.type}
          </text>
        </g>
      );
    }

    const SR_ONLY = {
      position: "absolute",
      width: 1,
      height: 1,
      margin: -1,
      padding: 0,
      overflow: "hidden",
      clip: "rect(0 0 0 0)",
      whiteSpace: "nowrap",
      border: 0,
    };

    return { VB_W, VB_H, NODE_W, NODE_H, STEP, BLUE, SLATE, ORANGE, GREEN, RED, SURFACE, BORDER, MONO, KIND_COLOR, NODES, NODE_BY_ID, HAPPY, EDGES, NODE_MS, EDGE_MS, PAUSE_MS, buildSegments, TIMELINES, timelineFor, smooth, frameAt, STATIC_SNAP, nodeStyle, edgeStyle, arrowPoints, Tooltip, SR_ONLY };
  }, []);
  const { VB_W, VB_H, NODE_W, NODE_H, STEP, BLUE, SLATE, ORANGE, GREEN, RED, SURFACE, BORDER, MONO, KIND_COLOR, NODES, NODE_BY_ID, HAPPY, EDGES, NODE_MS, EDGE_MS, PAUSE_MS, buildSegments, TIMELINES, timelineFor, smooth, frameAt, STATIC_SNAP, nodeStyle, edgeStyle, arrowPoints, Tooltip, SR_ONLY } = LIB;
  // Static by default (first paint, SSR, reduced motion); animation switches on after mount.
  const [reduced, setReduced] = useState(true);
  const [paused, setPaused] = useState(true);
  const [snap, setSnap] = useState(STATIC_SNAP);
  const [hover, setHover] = useState(null);
  const wrapRef = useRef(null);
  const edgeRefs = useRef({});
  const dotRefs = useRef([]);

  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) {
      return undefined;
    }
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReduced(mq.matches);
    update();
    if (mq.addEventListener) {
      mq.addEventListener("change", update);
      return () => mq.removeEventListener("change", update);
    }
    mq.addListener(update);
    return () => mq.removeListener(update);
  }, []);

  useEffect(() => {
    if (reduced) {
      setSnap(STATIC_SNAP);
      setPaused(true);
      return undefined;
    }
    const wrap = wrapRef.current;
    if (!wrap) {
      return undefined;
    }

    let visible = typeof IntersectionObserver === "undefined";
    let raf = 0;
    let running = false;
    let last = 0;
    let t = 0;
    let loop = 0;
    let lastKey = "";
    let dotsShown = false;
    const lens = {};

    const lenOf = (id) => {
      if (!lens[id]) {
        const p = edgeRefs.current[id];
        try {
          lens[id] = p && p.getTotalLength ? p.getTotalLength() : 0;
        } catch (e) {
          lens[id] = 0;
        }
      }
      return lens[id];
    };

    const hideDots = () => {
      dotRefs.current.forEach((d) => {
        if (d) {
          d.setAttribute("opacity", "0");
        }
      });
      dotsShown = false;
    };

    const moveDots = (dot) => {
      if (!dot) {
        if (dotsShown) {
          hideDots();
        }
        return;
      }
      const path = edgeRefs.current[dot.id];
      const len = lenOf(dot.id);
      if (!path || !len) {
        return;
      }
      dotRefs.current.forEach((d, i) => {
        if (!d) {
          return;
        }
        const p = dot.p - i * 0.15;
        if (p < 0 || p > 1) {
          d.setAttribute("opacity", "0");
          return;
        }
        const pt = path.getPointAtLength(p * len);
        d.setAttribute("cx", String(pt.x));
        d.setAttribute("cy", String(pt.y));
        d.setAttribute("opacity", i === 0 ? "1" : "0.5");
        d.style.fill = dot.err ? ORANGE : BLUE;
      });
      dotsShown = true;
    };

    const tick = (now) => {
      raf = requestAnimationFrame(tick);
      const dt = last ? Math.min(now - last, 100) : 0;
      last = now;
      t += dt;
      let tl = timelineFor(loop);
      while (t >= tl.total) {
        t -= tl.total;
        loop += 1;
        tl = timelineFor(loop);
      }
      const f = frameAt(tl, t);
      if (f.key !== lastKey) {
        lastKey = f.key;
        setSnap({ nodes: f.nodes, edges: f.edges });
      }
      moveDots(f.dot);
    };

    const sync = () => {
      const shouldRun = visible && !document.hidden;
      setPaused(!shouldRun);
      if (shouldRun && !running) {
        running = true;
        last = 0;
        raf = requestAnimationFrame(tick);
      } else if (!shouldRun && running) {
        running = false;
        cancelAnimationFrame(raf);
      }
    };

    let io = null;
    if (typeof IntersectionObserver !== "undefined") {
      io = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            visible = entry.isIntersecting;
          });
          sync();
        },
        { threshold: 0.1 }
      );
      io.observe(wrap);
    } else {
      sync();
    }
    document.addEventListener("visibilitychange", sync);

    return () => {
      running = false;
      cancelAnimationFrame(raf);
      document.removeEventListener("visibilitychange", sync);
      if (io) {
        io.disconnect();
      }
      hideDots();
    };
  }, [reduced]);

  const animating = !reduced && !paused;
  const hovered = hover ? NODE_BY_ID[hover] : null;

  return (
    <div
      ref={wrapRef}
      className="yk-dag-hero"
      style={{
        position: "relative",
        width: "100%",
        maxWidth: 820,
        margin: "1.5rem auto",
        aspectRatio: VB_W + " / " + VB_H,
      }}
    >
      <svg
        viewBox={"0 0 " + VB_W + " " + VB_H}
        role="img"
        aria-label="Animated example workflow: ticket, classify, approve, update, done, with an error path from classify to dead"
        aria-describedby="yk-dag-summary"
        style={{ display: "block", width: "100%", height: "100%", overflow: "visible" }}
      >
        {EDGES.map((e) => {
          const status = snap.edges[e.id] || "idle";
          const st = edgeStyle(status, e.err);
          return (
            <g key={e.id}>
              <path
                ref={(el) => {
                  edgeRefs.current[e.id] = el;
                }}
                d={"M" + e.x1 + "," + e.y1 + " L" + e.x2 + "," + e.y2}
                style={st}
              />
              <polygon
                points={arrowPoints(e)}
                style={{ fill: st.stroke, fillOpacity: st.strokeOpacity === undefined ? 1 : st.strokeOpacity, transition: "fill 250ms ease" }}
              />
            </g>
          );
        })}

        <text
          x={NODE_BY_ID.classify.x + NODE_W / 2 + 10}
          y={NODE_BY_ID.classify.y + NODE_H + 38}
          fontSize={11}
          fontFamily={MONO}
          style={{ fill: ORANGE }}
        >
          error
        </text>

        {NODES.map((n) => {
          const status = snap.nodes[n.id] || "pending";
          const cy = n.y + NODE_H / 2;
          return (
            <g
              key={n.id}
              tabIndex={0}
              aria-label={n.id + ", " + n.type}
              onMouseEnter={() => setHover(n.id)}
              onMouseLeave={() => setHover((h) => (h === n.id ? null : h))}
              onFocus={() => setHover(n.id)}
              onBlur={() => setHover((h) => (h === n.id ? null : h))}
            >
              {status === "running" && (
                <rect
                  x={n.x - 3}
                  y={n.y - 3}
                  width={NODE_W + 6}
                  height={NODE_H + 6}
                  rx={13}
                  fill="none"
                  strokeWidth={animating ? 1 : 2}
                  opacity={animating ? 0.7 : 0.35}
                  style={{ stroke: BLUE }}
                  pointerEvents="none"
                >
                  {animating && (
                    <>
                      <animate attributeName="opacity" values="0.7;0" dur="1.4s" repeatCount="indefinite" />
                      <animate attributeName="stroke-width" values="1;6" dur="1.4s" repeatCount="indefinite" />
                    </>
                  )}
                </rect>
              )}
              <rect x={n.x} y={n.y} width={NODE_W} height={NODE_H} rx={10} style={nodeStyle(status, n.id)} />
              <rect x={n.x + 9} y={n.y + 11} width={3} height={NODE_H - 22} rx={1.5} style={{ fill: KIND_COLOR[n.kind] }} />
              <text x={n.x + 20} y={cy + 4.5} fontSize={12.5} fontFamily={MONO} fill="currentColor">
                {n.id}
              </text>
              {(status === "done" || status === "failed") && (
                <text
                  x={n.x + NODE_W - 12}
                  y={cy + 4.5}
                  textAnchor="middle"
                  fontSize={12}
                  fontWeight={700}
                  style={{ fill: status === "failed" ? RED : n.id === "dead" ? ORANGE : GREEN }}
                >
                  {status === "failed" ? "✕" : "✓"}
                </text>
              )}
            </g>
          );
        })}

        {/* Message dots ride on top of the edges; positions are set imperatively per frame. */}
        {[0, 1].map((i) => (
          <circle
            key={i}
            ref={(el) => {
              dotRefs.current[i] = el;
            }}
            r={i === 0 ? 4.5 : 3.5}
            cx={0}
            cy={0}
            opacity={0}
            pointerEvents="none"
            style={{ fill: BLUE }}
          />
        ))}

        {hovered && <Tooltip node={hovered} />}
      </svg>
      <p id="yk-dag-summary" style={SR_ONLY}>
        Example workflow. The ticket node (API_TRIGGER) starts a run and passes a message to classify
        (LLM_CALL), which passes it to approve (WAIT_FOR_SIGNAL), then update (HTTP_REQUEST), and
        finally done (CONSOLE_TERMINAL). If classify fails, its error port routes the message to dead
        (CONSOLE_TERMINAL) instead.
      </p>
    </div>
  );
};
