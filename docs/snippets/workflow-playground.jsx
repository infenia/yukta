// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * Workflow playground: an in-browser SIMULATION of how Yukta runs a workflow.
 * Nothing here talks to a server. Sections:
 *   1. Pure logic   presets, formatting, validation, layering/layout, run simulation
 *   2. Styles       one <style> element, every class prefixed `ykp-`
 *   3. Small views  status icons
 *   4. Component    state, handlers and markup
 * The hooks (useState, useEffect, ...) are provided by the Mintlify runtime.
 */

/* ==========================================================================
 * 1. PURE LOGIC (no React, no DOM). Keep self-contained: it is copied into a
 *    scratch file and asserted with node.
 * ======================================================================== */
// PURE-START

export const WorkflowPlayground = () => {
  // Mintlify evaluates only the exported component, so everything it needs is declared inside it.
  const LIB = useMemo(() => {
    const NODE_W = 150;
    const NODE_H = 54;
    const GAP_X = 76;
    const GAP_Y = 26;
    const PAD_X = 20;
    const PAD_Y = 26;
    const MAX_NODES = 500;
    const MAX_EDGES = 2000;
    const STEP_MS = 600;

    const NODE_TYPES = [
      "API_TRIGGER",
      "HTTP_REQUEST",
      "MAPPER",
      "LLM_CALL",
      "BRANCH",
      "WAIT_FOR_SIGNAL",
      "NOTIFY_TERMINAL",
      "CONSOLE_TERMINAL",
    ];

    const node = (nodeId, type, config) => ({ nodeId, type, config: config || {} });
    const edge = (source, target, sourcePort) =>
      sourcePort ? { source, target, sourcePort } : { source, target };

    const FETCH_CONFIG = {
      url: "https://api.example.com/items",
      engine: { retry: { maxAttempts: 3, backoffMs: 200, maxBackoffMs: 5000 }, onError: "ROUTE" },
    };

    const PRESET_DEFS = {
      linear: {
        label: "Linear",
        nodes: [
          node("start", "API_TRIGGER"),
          node("fetch", "HTTP_REQUEST", { url: "https://api.example.com/items" }),
          node("shape", "MAPPER"),
          node("out", "CONSOLE_TERMINAL"),
        ],
        edges: [edge("start", "fetch"), edge("fetch", "shape"), edge("shape", "out")],
      },
      fanout: {
        label: "Fan-out and aggregate",
        nodes: [
          node("ingest", "API_TRIGGER"),
          node("fan-out", "RECIPIENT_LIST", {
            mode: "STATIC",
            recipients: ["primary", "backup", "audit"],
            parallel: true,
          }),
          node("fetch-primary", "HTTP_REQUEST"),
          node("fetch-backup", "HTTP_REQUEST"),
          node("audit-log", "CONSOLE_TERMINAL"),
          node("merge", "AGGREGATE"),
          node("summarize", "LLM_CALL"),
          node("report", "NOTIFY_TERMINAL"),
        ],
        edges: [
          edge("ingest", "fan-out"),
          edge("fan-out", "fetch-primary", "primary"),
          edge("fan-out", "fetch-backup", "backup"),
          edge("fan-out", "audit-log", "audit"),
          edge("fetch-primary", "merge"),
          edge("fetch-backup", "merge"),
          edge("merge", "summarize"),
          edge("summarize", "report"),
        ],
      },
      errorroute: {
        label: "Error route",
        nodes: [
          node("request", "API_TRIGGER"),
          node("fetch", "HTTP_REQUEST", FETCH_CONFIG),
          node("validate", "VALIDATE"),
          node("publish", "MAPPER"),
          node("done", "CONSOLE_TERMINAL"),
          node("rejected", "CONSOLE_TERMINAL"),
          node("alert", "NOTIFY_TERMINAL"),
        ],
        edges: [
          edge("request", "fetch"),
          edge("fetch", "validate"),
          edge("validate", "publish"),
          edge("publish", "done"),
          edge("validate", "rejected", "invalid"),
          edge("fetch", "alert", "error"),
        ],
      },
      approval: {
        label: "Approval",
        nodes: [
          node("ticket", "API_TRIGGER"),
          node("classify", "LLM_CALL", { engine: { onError: "ROUTE" } }),
          node("approve", "WAIT_FOR_SIGNAL", { signal: "approve" }),
          node("update", "HTTP_REQUEST"),
          node("done", "CONSOLE_TERMINAL"),
          node("dead", "CONSOLE_TERMINAL"),
        ],
        edges: [
          edge("ticket", "classify"),
          edge("classify", "approve"),
          edge("approve", "update"),
          edge("update", "done"),
          edge("classify", "dead", "error"),
        ],
      },
    };

    const PRESET_ORDER = ["linear", "fanout", "errorroute", "approval"];
    const DEFAULT_PRESET = "approval";

    /** Compact, readable JSON: one node or edge per line. */
    function fmt(v) {
      if (Array.isArray(v)) {
        return v.length ? "[" + v.map(fmt).join(", ") + "]" : "[]";
      }
      if (v && typeof v === "object") {
        const keys = Object.keys(v).filter((k) => v[k] !== undefined);
        return keys.length
          ? "{ " + keys.map((k) => JSON.stringify(k) + ": " + fmt(v[k])).join(", ") + " }"
          : "{}";
      }
      return JSON.stringify(v);
    }

    function formatWorkflow(nodes, edges) {
      const block = (items) =>
        items.length ? "\n" + items.map((i) => "    " + fmt(i)).join(",\n") + "\n  " : "";
      return '{\n  "nodes": [' + block(nodes) + '],\n  "edges": [' + block(edges) + "]\n}";
    }

    const isErrorEdge = (e) => e.sourcePort === "error";

    // Ports that carry the "other" outcome (a rejection, a failing exit code). A real router decides
    // per message; the simulation assumes the happy path, so these stay quiet unless the node fails.
    const ALT_PORTS = ["invalid", "failure", "reject", "discard"];

    /** True when adding source -> target would close a cycle (or is a self-loop). */
    function wouldCreateCycle(edges, source, target) {
      if (source === target) return true;
      const seen = new Set([target]);
      const stack = [target];
      while (stack.length) {
        const u = stack.pop();
        for (const e of edges) {
          if (e.source === u && !seen.has(e.target)) {
            if (e.target === source) return true;
            seen.add(e.target);
            stack.push(e.target);
          }
        }
      }
      return false;
    }

    /** A node that sits on a cycle, or null when the graph is acyclic (Kahn's algorithm). */
    function findCycleNode(ids, edges) {
      const indeg = new Map(ids.map((i) => [i, 0]));
      edges.forEach((e) => indeg.set(e.target, indeg.get(e.target) + 1));
      const queue = ids.filter((i) => indeg.get(i) === 0);
      const removed = new Set();
      while (queue.length) {
        const u = queue.shift();
        removed.add(u);
        for (const e of edges) {
          if (e.source === u) {
            indeg.set(e.target, indeg.get(e.target) - 1);
            if (indeg.get(e.target) === 0) queue.push(e.target);
          }
        }
      }
      const left = ids.filter((i) => !removed.has(i));
      if (!left.length) return null;
      // Every leftover node has a leftover predecessor; walking back must revisit a node on a cycle.
      const leftSet = new Set(left);
      let cur = left[0];
      const seen = new Set();
      while (!seen.has(cur)) {
        seen.add(cur);
        const back = edges.find((e) => e.target === cur && leftSet.has(e.source));
        if (!back) break;
        cur = back.source;
      }
      return cur;
    }

    /** Parse the editor text. Returns { errors, graph } where graph is null if anything is wrong. */
    function validateWorkflowText(text) {
      const bad = (errors) => ({ errors, graph: null });
      let data;
      try {
        data = JSON.parse(text);
      } catch (err) {
        return bad(["JSON error: " + (err && err.message ? err.message : String(err))]);
      }
      let wf = data;
      if (
        data &&
        typeof data === "object" &&
        !Array.isArray(data) &&
        data.workflows &&
        typeof data.workflows === "object"
      ) {
        const first = Object.values(data.workflows)[0];
        if (!first) return bad(['"workflows" is empty.']);
        wf = first;
      }
      if (!wf || typeof wf !== "object" || Array.isArray(wf)) {
        return bad(['Expected an object with "nodes" and "edges".']);
      }
      const errors = [];
      const rawNodes = wf.nodes === undefined ? [] : wf.nodes;
      const rawEdges = wf.edges === undefined ? [] : wf.edges;
      if (!Array.isArray(rawNodes)) errors.push('"nodes" must be an array.');
      if (!Array.isArray(rawEdges)) errors.push('"edges" must be an array.');
      if (errors.length) return bad(errors);
      if (rawNodes.length === 0) return bad(["The workflow has no nodes. Add at least one node."]);
      if (rawNodes.length > MAX_NODES) errors.push("Too many nodes (limit " + MAX_NODES + ").");
      if (rawEdges.length > MAX_EDGES) errors.push("Too many edges (limit " + MAX_EDGES + ").");

      const nodes = [];
      const ids = new Set();
      const dupReported = new Set();
      rawNodes.forEach((n, i) => {
        if (!n || typeof n !== "object" || typeof n.nodeId !== "string" || n.nodeId === "") {
          errors.push("Node #" + (i + 1) + ' needs a non-empty string "nodeId".');
          return;
        }
        if (ids.has(n.nodeId)) {
          if (!dupReported.has(n.nodeId)) {
            errors.push('Duplicate nodeId "' + n.nodeId + '".');
            dupReported.add(n.nodeId);
          }
          return;
        }
        ids.add(n.nodeId);
        if (typeof n.type !== "string" || n.type === "") {
          errors.push('Node "' + n.nodeId + '" needs a "type" (for example HTTP_REQUEST).');
        }
        nodes.push(n);
      });

      const edges = [];
      const edgeKeys = new Set();
      rawEdges.forEach((e, i) => {
        if (!e || typeof e !== "object" || typeof e.source !== "string" || typeof e.target !== "string") {
          errors.push("Edge #" + (i + 1) + ' needs string "source" and "target".');
          return;
        }
        if (e.sourcePort !== undefined && typeof e.sourcePort !== "string") {
          errors.push("Edge " + e.source + " -> " + e.target + ': "sourcePort" must be a string.');
          return;
        }
        let ok = true;
        if (!ids.has(e.source)) {
          errors.push("Edge " + e.source + " -> " + e.target + ': unknown source node "' + e.source + '".');
          ok = false;
        }
        if (!ids.has(e.target)) {
          errors.push("Edge " + e.source + " -> " + e.target + ': unknown target node "' + e.target + '".');
          ok = false;
        }
        if (ok && e.source === e.target) {
          errors.push('Edge from "' + e.source + '" to itself is a cycle (self-loop).');
          ok = false;
        }
        const key = e.source + "\u0000" + e.target + "\u0000" + (e.sourcePort || "");
        if (ok && edgeKeys.has(key)) {
          errors.push("Duplicate edge " + e.source + " -> " + e.target + ".");
          ok = false;
        }
        if (ok) {
          edgeKeys.add(key);
          edges.push(e);
        }
      });

      if (!errors.length) {
        const c = findCycleNode(
          nodes.map((n) => n.nodeId),
          edges
        );
        if (c !== null) errors.push('The graph has a cycle through node "' + c + '". Workflows must be acyclic.');
      }
      return errors.length ? bad(errors) : { errors: [], graph: { nodes, edges } };
    }

    /** Layered left-to-right layout: layer = longest path from a root, order by barycenter. */
    function computeLayout(graph) {
      const { nodes, edges } = graph;
      const ids = nodes.map((n) => n.nodeId);
      const preds = new Map(ids.map((i) => [i, []]));
      const succs = new Map(ids.map((i) => [i, []]));
      const rem = new Map(ids.map((i) => [i, 0]));
      edges.forEach((e) => {
        preds.get(e.target).push(e.source);
        succs.get(e.source).push(e.target);
        rem.set(e.target, rem.get(e.target) + 1);
      });
      const layerOf = new Map(ids.map((i) => [i, 0]));
      const queue = ids.filter((i) => rem.get(i) === 0);
      while (queue.length) {
        const u = queue.shift();
        for (const v of succs.get(u)) {
          layerOf.set(v, Math.max(layerOf.get(v), layerOf.get(u) + 1));
          rem.set(v, rem.get(v) - 1);
          if (rem.get(v) === 0) queue.push(v);
        }
      }
      const layerCount = Math.max(0, ...ids.map((i) => layerOf.get(i))) + 1;
      const layers = Array.from({ length: layerCount }, () => []);
      ids.forEach((i) => layers[layerOf.get(i)].push(i));
      const colHeight = (n) => n * NODE_H + Math.max(0, n - 1) * GAP_Y;
      const maxH = Math.max(...layers.map((l) => colHeight(l.length)));
      const order = new Map(ids.map((i, k) => [i, k]));
      const centerY = new Map();
      layers.forEach((layer, L) => {
        if (L > 0) {
          const bary = (id) => {
            const ps = preds.get(id).filter((p) => centerY.has(p));
            return ps.length ? ps.reduce((a, p) => a + centerY.get(p), 0) / ps.length : Infinity;
          };
          layer.sort((a, b) => {
            const d = bary(a) - bary(b);
            return Number.isFinite(d) && d !== 0 ? d : order.get(a) - order.get(b);
          });
        }
        const top = PAD_Y + (maxH - colHeight(layer.length)) / 2;
        layer.forEach((id, k) => centerY.set(id, top + k * (NODE_H + GAP_Y) + NODE_H / 2));
      });
      const pos = {};
      layers.forEach((layer, L) =>
        layer.forEach((id) => {
          pos[id] = { x: PAD_X + L * (NODE_W + GAP_X), y: centerY.get(id) - NODE_H / 2, layer: L };
        })
      );
      const edgePaths = edges.map((e) => {
        const s = pos[e.source];
        const t = pos[e.target];
        const x1 = s.x + NODE_W;
        const y1 = s.y + NODE_H / 2;
        const x2 = t.x;
        const y2 = t.y + NODE_H / 2;
        const dx = Math.max(28, (x2 - x1) / 2);
        return {
          d: "M" + x1 + "," + y1 + " C" + (x1 + dx) + "," + y1 + " " + (x2 - dx) + "," + y2 + " " + x2 + "," + y2,
          lx: (x1 + x2) / 2,
          ly: (y1 + y2) / 2,
        };
      });
      return {
        pos,
        edgePaths,
        layers,
        width: PAD_X * 2 + layerCount * NODE_W + (layerCount - 1) * GAP_X,
        height: PAD_Y * 2 + maxH,
      };
    }

    function buildIndex(graph) {
      const out = new Map(graph.nodes.map((n) => [n.nodeId, []]));
      const inc = new Map(graph.nodes.map((n) => [n.nodeId, []]));
      graph.edges.forEach((e, i) => {
        out.get(e.source).push(i);
        inc.get(e.target).push(i);
      });
      return { out, inc };
    }

    const joinRuleOf = (n) =>
      (n.config && n.config.engine && n.config.engine.trigger) || "ALL_SUCCESS";

    /** A fresh run: every node PENDING. Edge states: pending | live | failed | idle. */
    function initRun(graph) {
      const status = {};
      graph.nodes.forEach((n) => {
        status[n.nodeId] = "PENDING";
      });
      return {
        step: 0,
        done: false,
        result: null,
        status,
        edge: graph.edges.map(() => "pending"),
        routed: [],
        failed: [],
        idle: [],
        moves: [],
        message: "Ready. Press Run or Step.",
      };
    }

    /**
     * Advance the simulation by one step:
     *   1. every RUNNING node finishes (SUCCESS, or FAILURE if marked to fail) and sends on its edges;
     *   2. every node whose inputs are all settled starts RUNNING, or becomes UPSTREAM_FAILED.
     * Simplifications: a node waits for all its active inputs; named ports fire on a normal run
     * (except invalid/failure/reject/discard, which are assumed to carry the unhappy path); only the
     * `error` port is followed when a node is marked to fail.
     */
    function stepRun(graph, failIds, run) {
      if (run.done) return run;
      const { out, inc } = buildIndex(graph);
      const status = { ...run.status };
      const edgeState = run.edge.slice();
      const routed = run.routed.slice();
      const failed = run.failed.slice();
      const idle = run.idle.slice();
      const notes = [];
      const moves = [];
      const step = run.step + 1;
      let terminalFailure = null;

      graph.nodes
        .map((n) => n.nodeId)
        .filter((id) => status[id] === "RUNNING")
        .forEach((id) => {
          const fails = failIds.includes(id);
          const outs = out.get(id);
          const hasErrorEdge = outs.some((i) => isErrorEdge(graph.edges[i]));
          status[id] = fails ? "FAILURE" : "SUCCESS";
          if (fails && hasErrorEdge) {
            routed.push(id);
          } else if (fails) {
            failed.push(id);
            if (outs.length === 0 && terminalFailure === null) terminalFailure = id;
          }
          outs.forEach((i) => {
            const port = graph.edges[i].sourcePort;
            if (isErrorEdge(graph.edges[i])) {
              edgeState[i] = fails ? "live" : "idle";
            } else if (fails) {
              edgeState[i] = "failed";
            } else {
              edgeState[i] = ALT_PORTS.includes(port) ? "idle" : "live";
            }
            if (edgeState[i] === "live") moves.push(i);
          });
          notes.push(id + " " + status[id] + (fails && hasErrorEdge ? " (routed to the error port)" : ""));
        });

      const started = [];
      if (terminalFailure !== null) {
        // A failing terminal ends the whole execution at once; everything unfinished is cancelled.
        graph.nodes.forEach((n) => {
          if (status[n.nodeId] === "PENDING" || status[n.nodeId] === "RUNNING") {
            status[n.nodeId] = "CANCELLED";
          }
        });
        notes.push("terminal " + terminalFailure + " failed, so the remaining nodes are CANCELLED");
      } else {
        let changed = true;
        while (changed) {
          changed = false;
          for (const n of graph.nodes) {
            const id = n.nodeId;
            if (status[id] !== "PENDING" || idle.includes(id)) continue;
            const ins = inc.get(id);
            if (ins.some((i) => edgeState[i] === "pending")) continue;
            const live = ins.filter((i) => edgeState[i] === "live").length;
            const bad = ins.filter((i) => edgeState[i] === "failed").length;
            const strict = joinRuleOf(n) === "ALL_SUCCESS";
            let mode;
            if (ins.length === 0) mode = "run";
            else if (bad > 0 && strict) mode = "upstream";
            else if (live > 0) mode = "run";
            else if (bad > 0) mode = "upstream";
            else mode = "idle";
            if (mode === "run") {
              status[id] = "RUNNING";
              started.push(id);
            } else if (mode === "upstream") {
              status[id] = "UPSTREAM_FAILED";
              notes.push(id + " UPSTREAM_FAILED");
              out.get(id).forEach((i) => {
                edgeState[i] = "failed";
              });
            } else {
              idle.push(id);
              out.get(id).forEach((i) => {
                edgeState[i] = "idle";
              });
            }
            changed = true;
          }
        }
      }

      const done = !graph.nodes.some((n) => status[n.nodeId] === "RUNNING");
      const result = done ? (failed.length ? "FAILURE" : "SUCCESS") : null;
      let message = "Step " + step + ": " + (notes.length ? notes.join("; ") : "starting");
      if (started.length) message += "; now RUNNING: " + started.join(", ");
      if (done) {
        message += failed.length
          ? ". Run FAILURE at " + failed.join(", ") + "."
          : routed.length
            ? ". Run SUCCESS via the error route from " + routed.join(", ") + "."
            : ". Run SUCCESS.";
      }
      return { step, done, result, status, edge: edgeState, routed, failed, idle, moves, message };
    }

    /** One summary line for the run. */
    function summarizeRun(graph, run) {
      if (!run || run.step === 0) return "Not started.";
      const counts = {};
      graph.nodes.forEach((n) => {
        const s = run.status[n.nodeId];
        counts[s] = (counts[s] || 0) + 1;
      });
      if (!run.done) {
        return "Step " + run.step + ": " + (counts.RUNNING || 0) + " running, " + (counts.SUCCESS || 0) + " finished.";
      }
      const parts = [];
      ["SUCCESS", "FAILURE", "UPSTREAM_FAILED", "CANCELLED"].forEach((s) => {
        if (counts[s]) parts.push(counts[s] + " " + s);
      });
      if (counts.PENDING) parts.push(counts.PENDING + " not reached");
      const head = run.failed.length
        ? "Run FAILURE at " + run.failed.join(", ")
        : run.routed.length
          ? "Run SUCCESS via the error route from " + run.routed.join(", ")
          : "Run SUCCESS";
      return head + " in " + run.step + " steps: " + parts.join(", ") + ".";
    }

    // PURE-END

    /* ==========================================================================
     * 2. STYLES
     * ======================================================================== */

    const CSS = `
    .ykp {
      --ykp-fg: #1b2330;
      --ykp-card: #ffffff;
      --ykp-surface: var(--yk-surface, #f0f4f9);
      --ykp-border: var(--yk-border, #dee1e8);
      --ykp-muted: var(--yk-muted, #545e6f);
      --ykp-blue: var(--yk-blue, #2f6bff);
      --ykp-slate: var(--yk-slate, #6a7a9b);
      --ykp-orange: var(--yk-orange, #e85d0c);
      --ykp-orange-text: #b3430a;
      --ykp-ok: var(--yk-success, #008235);
      --ykp-bad: var(--yk-danger, #e7000b);
      --ykp-radius: var(--yk-radius-lg, 0.625rem);
      --ykp-radius-lg: var(--yk-radius-xl, 0.875rem);
      color: var(--ykp-fg);
      min-height: 640px;
      margin: 1.5rem 0;
      font-size: 14px;
      line-height: 1.5;
      letter-spacing: 0;
    }
    .dark .ykp {
      --ykp-fg: #e6eaf2;
      --ykp-card: #11161d;
      --ykp-surface: var(--yk-surface, #1c222b);
      --ykp-border: var(--yk-border, #2d333d);
      --ykp-muted: var(--yk-muted, #9aa5b8);
      --ykp-orange: var(--yk-orange, #fc8c45);
      --ykp-orange-text: var(--yk-orange, #fc8c45);
      --ykp-ok: var(--yk-success, #4bcb71);
      --ykp-bad: var(--yk-danger, #ff6467);
    }
    .ykp *, .ykp *::before, .ykp *::after { box-sizing: border-box; }
    .ykp ul, .ykp li, .ykp p { margin: 0; padding: 0; list-style: none; }

    .ykp-banner {
      border: 1px solid color-mix(in srgb, var(--ykp-blue) 35%, transparent);
      background: color-mix(in srgb, var(--ykp-blue) 8%, transparent);
      border-radius: var(--ykp-radius-lg);
      padding: 0.6rem 0.9rem;
      margin-bottom: 0.9rem;
    }
    .ykp-banner strong { font-weight: 600; }

    .ykp-bar {
      display: flex; flex-wrap: wrap; gap: 0.5rem 0.9rem; align-items: flex-end;
      margin-bottom: 0.6rem;
    }
    .ykp-group { display: flex; flex-wrap: wrap; gap: 0.4rem; align-items: flex-end; }
    .ykp-field { display: flex; flex-direction: column; gap: 2px; }
    .ykp-label { font-size: 11px; font-weight: 600; color: var(--ykp-muted); text-transform: uppercase; letter-spacing: 0.04em; }
    .ykp-select, .ykp-btn {
      font: inherit; font-size: 13px; color: var(--ykp-fg);
      background: var(--ykp-card); border: 1px solid var(--ykp-border);
      border-radius: var(--ykp-radius); padding: 0.35rem 0.7rem; min-height: 34px;
    }
    .ykp-select { padding-right: 0.4rem; max-width: 100%; }
    .ykp-btn { cursor: pointer; font-weight: 500; }
    .ykp-btn:hover:not(:disabled) { border-color: var(--ykp-blue); }
    .ykp-btn:disabled, .ykp-select:disabled { opacity: 0.5; cursor: not-allowed; }
    .ykp-btn-primary { background: var(--ykp-blue); border-color: var(--ykp-blue); color: #fff; }
    .ykp-btn-primary:hover:not(:disabled) { filter: brightness(1.08); border-color: var(--ykp-blue); }
    .ykp-btn[aria-pressed="true"] {
      border-color: var(--ykp-bad); color: var(--ykp-bad);
      background: color-mix(in srgb, var(--ykp-bad) 10%, var(--ykp-card));
    }
    .ykp-btn-primary[aria-pressed="true"] { color: #fff; }
    .ykp-select:focus-visible, .ykp-btn:focus-visible, .ykp-textarea:focus-visible, .ykp a:focus-visible {
      outline: 2px solid var(--ykp-blue); outline-offset: 2px;
    }

    .ykp-hint { min-height: 1.5em; color: var(--ykp-muted); margin-bottom: 0.5rem; font-size: 13px; }
    .ykp-hint-warn { color: var(--ykp-orange-text); font-weight: 500; }

    .ykp-run {
      display: flex; flex-wrap: wrap; gap: 0.4rem 0.6rem; align-items: center;
      margin-bottom: 0.6rem;
    }
    .ykp-step {
      font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px; padding: 0.2rem 0.55rem; border-radius: 999px;
      border: 1px solid var(--ykp-border); background: var(--ykp-surface);
    }
    .ykp-summary { flex: 1 1 240px; font-size: 13px; }

    .ykp-graph {
      position: relative; border: 1px solid var(--ykp-border); border-radius: var(--ykp-radius-lg);
      background: var(--ykp-surface); overflow-x: auto; min-height: 260px; padding: 0.4rem;
    }
    .ykp-graph-stale .ykp-svg { opacity: 0.45; filter: grayscale(1); pointer-events: none; }
    .ykp-svg { display: block; width: 100%; height: auto; user-select: none; -webkit-user-select: none; }

    .ykp-edge { fill: none; stroke: var(--ykp-slate); stroke-width: 1.6; }
    .ykp-edge-err { stroke: var(--ykp-orange); stroke-dasharray: 6 4; stroke-width: 1.8; }
    .ykp-edge-live { stroke: var(--ykp-blue); stroke-width: 2.6; }
    .ykp-edge-err.ykp-edge-live { stroke: var(--ykp-orange); }
    .ykp-edge-dim { opacity: 0.35; }
    .ykp-arr { fill: var(--ykp-slate); }
    .ykp-arr-live { fill: var(--ykp-blue); }
    .ykp-arr-err { fill: var(--ykp-orange); }
    .ykp-elabel {
      font: 600 10px "JetBrains Mono", ui-monospace, monospace; fill: var(--ykp-muted);
      paint-order: stroke; stroke: var(--ykp-surface); stroke-width: 4px; stroke-linejoin: round;
    }
    .ykp-elabel-err { fill: var(--ykp-orange-text); }
    .ykp-dot { fill: var(--ykp-blue); stroke: var(--ykp-card); stroke-width: 1.5; }

    .ykp-node { cursor: pointer; outline: none; }
    .ykp-node[aria-disabled="true"] { cursor: default; }
    .ykp-box { fill: var(--ykp-card); stroke: var(--ykp-border); stroke-width: 1.6; transition: stroke 0.15s, fill 0.15s; }
    .ykp-node:hover .ykp-box { stroke: var(--ykp-slate); }
    .ykp-node:focus-visible .ykp-box { stroke: var(--ykp-blue); stroke-width: 3; }
    .ykp-node:focus-visible { outline: 2px solid var(--ykp-blue); outline-offset: 3px; border-radius: 12px; }
    .ykp-halo { fill: none; stroke: var(--ykp-blue); stroke-width: 2; stroke-dasharray: 5 4; }
    .ykp-st-RUNNING .ykp-box { stroke: var(--ykp-blue); stroke-width: 2.4; }
    .ykp-st-SUCCESS .ykp-box { stroke: var(--ykp-ok); fill: color-mix(in srgb, var(--ykp-ok) 9%, var(--ykp-card)); }
    .ykp-st-FAILURE .ykp-box { stroke: var(--ykp-bad); stroke-width: 2.4; fill: color-mix(in srgb, var(--ykp-bad) 9%, var(--ykp-card)); }
    .ykp-st-UPSTREAM_FAILED .ykp-box, .ykp-st-CANCELLED .ykp-box {
      stroke: var(--ykp-slate); stroke-dasharray: 4 3; fill: var(--ykp-surface);
    }
    .ykp-st-UPSTREAM_FAILED .ykp-nid, .ykp-st-CANCELLED .ykp-nid { opacity: 0.7; }
    .ykp-nid { font: 600 13px Inter, system-ui, sans-serif; fill: var(--ykp-fg); }
    .ykp-ntype { font: 500 10px "JetBrains Mono", ui-monospace, monospace; fill: var(--ykp-muted); }
    .ykp-flag { fill: var(--ykp-bad); }
    .ykp-flag-text { font: 700 9px Inter, system-ui, sans-serif; fill: #fff; letter-spacing: 0.04em; }
    .ykp-ic-ring { fill: none; stroke: var(--ykp-slate); stroke-width: 1.8; }
    .ykp-ic-run { fill: none; stroke: var(--ykp-blue); stroke-width: 2; stroke-dasharray: 8 6; }
    .ykp-ic-dot { fill: var(--ykp-blue); }
    .ykp-ic-ok { fill: var(--ykp-ok); }
    .ykp-ic-bad { fill: var(--ykp-bad); }
    .ykp-ic-mark { fill: none; stroke: #fff; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
    .ykp-ic-gray { fill: none; stroke: var(--ykp-slate); stroke-width: 2; stroke-linecap: round; }
    @media (prefers-reduced-motion: no-preference) {
      .ykp-ic-run { transform-box: fill-box; transform-origin: center; animation: ykp-spin 1.4s linear infinite; }
    }
    @keyframes ykp-spin { to { transform: rotate(360deg); } }
    @media (prefers-reduced-motion: reduce) {
      .ykp-dot { display: none; }
      .ykp-box { transition: none; }
    }

    .ykp-legend { display: flex; flex-wrap: wrap; gap: 0.35rem 1.1rem; margin: 0.7rem 0 0.2rem; font-size: 12.5px; }
    .ykp-legend li { display: inline-flex; align-items: center; gap: 0.4rem; }
    .ykp-code { font-family: "JetBrains Mono", ui-monospace, monospace; font-size: 11.5px; }
    .ykp-swatch { display: inline-block; width: 26px; height: 0; border-top: 2px solid var(--ykp-slate); }
    .ykp-swatch-err { border-top: 2px dashed var(--ykp-orange); }
    .ykp-swatch-live { border-top: 3px solid var(--ykp-blue); }

    .ykp-h { font-size: 13px; font-weight: 600; margin: 1.1rem 0 0.4rem; display: block; }
    .ykp-textarea {
      display: block; width: 100%; min-height: 250px; resize: vertical;
      font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px;
      line-height: 1.55; tab-size: 2; white-space: pre; overflow: auto;
      color: var(--ykp-fg); background: var(--ykp-card);
      border: 1px solid var(--ykp-border); border-radius: var(--ykp-radius-lg); padding: 0.7rem 0.85rem;
    }
    .ykp-textarea[aria-invalid="true"] { border-color: var(--ykp-bad); }
    .ykp-errors {
      margin-top: 0.5rem; padding: 0.55rem 0.85rem; border-radius: var(--ykp-radius-lg);
      border: 1px solid color-mix(in srgb, var(--ykp-bad) 40%, transparent);
      background: color-mix(in srgb, var(--ykp-bad) 8%, transparent);
    }
    .ykp-errors li { padding-left: 1rem; position: relative; font-size: 13px; }
    .ykp-errors li::before { content: "\\00d7"; position: absolute; left: 0; color: var(--ykp-bad); font-weight: 700; }
    .ykp-ok { margin-top: 0.4rem; font-size: 12.5px; color: var(--ykp-muted); }

    .ykp-tablewrap { overflow-x: auto; border: 1px solid var(--ykp-border); border-radius: var(--ykp-radius-lg); }
    .ykp .ykp-table { width: 100%; border-collapse: collapse; margin: 0; font-size: 13px; }
    .ykp .ykp-table th, .ykp .ykp-table td {
      text-align: left; padding: 0.4rem 0.75rem; border: 0; border-bottom: 1px solid var(--ykp-border);
      vertical-align: middle; background: transparent; color: var(--ykp-fg);
    }
    .ykp .ykp-table th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--ykp-muted); background: var(--ykp-surface); }
    .ykp .ykp-table tr:last-child td { border-bottom: 0; }
    .ykp-st-cell { display: inline-flex; align-items: center; gap: 0.45rem; }
    .ykp-sel-row td:first-child { box-shadow: inset 3px 0 0 var(--ykp-blue); }

    .ykp-foot { margin-top: 1.1rem; font-size: 13px; color: var(--ykp-muted); }
    .ykp-foot a { color: var(--ykp-blue); text-decoration: underline; text-underline-offset: 2px; }

    .ykp-sr {
      position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
      clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
    }
    `;

    /* ==========================================================================
     * 3. SMALL VIEWS
     * ======================================================================== */

    /** Status glyph. Shape, not just colour: ring, spinner, check, cross, minus, slash. */
    const StatusIcon = ({ status, cx, cy }) => {
      const t = "translate(" + cx + "," + cy + ")";
      switch (status) {
        case "RUNNING":
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-run" r="7" />
              <circle className="ykp-ic-dot" r="2.6" />
            </g>
          );
        case "SUCCESS":
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-ok" r="7.5" />
              <path className="ykp-ic-mark" d="M-3.4,0.2 L-1,2.8 L3.6,-2.6" />
            </g>
          );
        case "FAILURE":
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-bad" r="7.5" />
              <path className="ykp-ic-mark" d="M-3,-3 L3,3 M3,-3 L-3,3" />
            </g>
          );
        case "UPSTREAM_FAILED":
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-ring" r="7" />
              <path className="ykp-ic-gray" d="M-3.4,0 L3.4,0" />
            </g>
          );
        case "CANCELLED":
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-ring" r="7" />
              <path className="ykp-ic-gray" d="M-3.6,3.6 L3.6,-3.6" />
            </g>
          );
        default:
          return (
            <g transform={t} aria-hidden="true">
              <circle className="ykp-ic-ring" r="6.5" />
            </g>
          );
      }
    };

    const MiniIcon = ({ status }) => (
      <svg width="18" height="18" viewBox="-10 -10 20 20" aria-hidden="true" focusable="false">
        <StatusIcon status={status} cx={0} cy={0} />
      </svg>
    );

    const LEGEND = [
      ["PENDING", "waiting (after the run: not reached)"],
      ["RUNNING", "active"],
      ["SUCCESS", "finished normally"],
      ["FAILURE", "failed"],
      ["UPSTREAM_FAILED", "never ran, an upstream node failed"],
      ["CANCELLED", "cut off when a terminal failed"],
    ];

    const truncate = (s, n) => (s.length > n ? s.slice(0, n - 1) + "…" : s);

    /* ==========================================================================
     * 4. COMPONENT
     * ======================================================================== */

    return { NODE_W, NODE_H, GAP_X, GAP_Y, PAD_X, PAD_Y, MAX_NODES, MAX_EDGES, STEP_MS, NODE_TYPES, node, edge, FETCH_CONFIG, PRESET_DEFS, PRESET_ORDER, DEFAULT_PRESET, fmt, formatWorkflow, isErrorEdge, ALT_PORTS, wouldCreateCycle, findCycleNode, validateWorkflowText, computeLayout, buildIndex, joinRuleOf, initRun, stepRun, summarizeRun, CSS, StatusIcon, MiniIcon, LEGEND, truncate };
  }, []);
  const { NODE_W, NODE_H, GAP_X, GAP_Y, PAD_X, PAD_Y, MAX_NODES, MAX_EDGES, STEP_MS, NODE_TYPES, node, edge, FETCH_CONFIG, PRESET_DEFS, PRESET_ORDER, DEFAULT_PRESET, fmt, formatWorkflow, isErrorEdge, ALT_PORTS, wouldCreateCycle, findCycleNode, validateWorkflowText, computeLayout, buildIndex, joinRuleOf, initRun, stepRun, summarizeRun, CSS, StatusIcon, MiniIcon, LEGEND, truncate } = LIB;
  const initialText = formatWorkflow(
    PRESET_DEFS[DEFAULT_PRESET].nodes,
    PRESET_DEFS[DEFAULT_PRESET].edges
  );
  const [presetKey, setPresetKey] = useState(DEFAULT_PRESET);
  const [text, setText] = useState(initialText);
  const [graph, setGraph] = useState(() => validateWorkflowText(initialText).graph);
  const [errors, setErrors] = useState([]);
  const [run, setRun] = useState(null);
  const [playing, setPlaying] = useState(false);
  const [failIds, setFailIds] = useState([]);
  const [selected, setSelected] = useState(null);
  const [notice, setNotice] = useState("");
  const [live, setLive] = useState("");
  const [newType, setNewType] = useState("HTTP_REQUEST");
  const [connectPort, setConnectPort] = useState("");
  const [reduced, setReduced] = useState(false);

  const parsedRef = useRef(initialText);
  const graphRef = useRef(graph);
  const failRef = useRef([]);

  const editable = errors.length === 0;
  const layout = useMemo(() => computeLayout(graph), [graph]);
  const activeFail = useMemo(() => {
    const ids = new Set(graph.nodes.map((n) => n.nodeId));
    return failIds.filter((id) => ids.has(id));
  }, [failIds, graph]);
  graphRef.current = graph;
  failRef.current = activeFail;

  const statusOf = (id) => (run ? run.status[id] : "PENDING");
  const tell = (msg) => {
    setNotice(msg);
    setLive(msg);
  };

  // Respect prefers-reduced-motion for the travelling message dots.
  useEffect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return undefined;
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sync = () => setReduced(mq.matches);
    sync();
    if (mq.addEventListener) mq.addEventListener("change", sync);
    return () => {
      if (mq.removeEventListener) mq.removeEventListener("change", sync);
    };
  }, []);

  // Debounced parse of the editor text.
  useEffect(() => {
    if (text === parsedRef.current) return undefined;
    const timer = setTimeout(() => {
      parsedRef.current = text;
      const res = validateWorkflowText(text);
      setErrors(res.errors);
      if (res.graph) {
        setGraph(res.graph);
        setRun(null);
        setPlaying(false);
        setSelected(null);
      } else {
        setPlaying(false);
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [text]);

  // Auto-play: one step per tick; stop when the run is done.
  useEffect(() => {
    if (!playing) return undefined;
    const timer = setInterval(() => {
      setRun((prev) => stepRun(graphRef.current, failRef.current, prev || initRun(graphRef.current)));
    }, STEP_MS);
    return () => clearInterval(timer);
  }, [playing]);

  useEffect(() => {
    if (run && run.done && playing) setPlaying(false);
  }, [run, playing]);

  // Announce each step result through the single live region.
  useEffect(() => {
    if (run && run.step > 0) setLive(run.message);
  }, [run]);

  // Esc cancels a pending connection.
  useEffect(() => {
    if (selected === null) return undefined;
    const onKey = (ev) => {
      if (ev.key === "Escape") {
        setSelected(null);
        tell("Connection cancelled.");
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [selected]);

  /* ---- graph edits: always rewrite the JSON so text and graph never disagree ---- */
  const commit = (g) => {
    const t = formatWorkflow(g.nodes, g.edges);
    parsedRef.current = t;
    setText(t);
    setGraph(g);
    setErrors([]);
    setRun(null);
    setPlaying(false);
  };

  const onPreset = (key) => {
    setPresetKey(key);
    const def = PRESET_DEFS[key];
    commit(validateWorkflowText(formatWorkflow(def.nodes, def.edges)).graph);
    setFailIds([]);
    setSelected(null);
    tell("Loaded the " + def.label + " preset.");
  };

  const connect = (source, target) => {
    if (graph.edges.some((e) => e.source === source && e.target === target)) {
      tell("An edge " + source + " -> " + target + " already exists.");
      return;
    }
    if (wouldCreateCycle(graph.edges, source, target)) {
      tell("Cannot connect " + source + " -> " + target + ": it would create a cycle.");
      return;
    }
    commit({ nodes: graph.nodes, edges: [...graph.edges, edge(source, target, connectPort)] });
    tell("Connected " + source + " -> " + target + (connectPort ? " on port " + connectPort : "") + ".");
  };

  const toggleFail = (id) => {
    const on = !failIds.includes(id);
    setFailIds((f) => (on ? [...f, id] : f.filter((x) => x !== id)));
    tell(id + (on ? " will fail when it runs." : " will succeed when it runs."));
  };

  const activate = (id, ev) => {
    if (!editable) return;
    if (ev.shiftKey) {
      toggleFail(id);
    } else if (selected === null) {
      setSelected(id);
      tell("Selected " + id + ". Choose another node to connect to it, or press Esc to cancel.");
    } else if (selected === id) {
      setSelected(null);
      tell("Deselected " + id + ".");
    } else {
      connect(selected, id);
      setSelected(null);
    }
  };

  const addNode = () => {
    let n = 1;
    const ids = new Set(graph.nodes.map((x) => x.nodeId));
    while (ids.has("node-" + n)) n += 1;
    const id = "node-" + n;
    commit({ nodes: [...graph.nodes, node(id, newType)], edges: graph.edges });
    setSelected(id);
    tell("Added " + id + " (" + newType + ") and selected it. Choose a node to connect it to.");
  };

  const removeSelected = () => {
    if (selected === null) return;
    if (graph.nodes.length <= 1) {
      tell("A workflow needs at least one node.");
      return;
    }
    commit({
      nodes: graph.nodes.filter((x) => x.nodeId !== selected),
      edges: graph.edges.filter((e) => e.source !== selected && e.target !== selected),
    });
    setFailIds((f) => f.filter((x) => x !== selected));
    tell("Removed " + selected + " and its edges.");
    setSelected(null);
  };

  /* ---- run controls ---- */
  const onRun = () => {
    const base = !run || run.done ? initRun(graph) : run;
    setRun(stepRun(graph, activeFail, base));
    setPlaying(true);
  };
  const onStep = () => {
    setPlaying(false);
    setRun(stepRun(graph, activeFail, run || initRun(graph)));
  };
  const onPause = () => {
    setPlaying(false);
    tell("Paused.");
  };
  const onReset = () => {
    setPlaying(false);
    setRun(null);
    tell("Run reset.");
  };

  const finished = Boolean(run && run.done);
  const hint = notice
    ? notice
    : "Click a node, then another node, to connect them. Shift-click a node (or use the Fail buttons below) to make it fail.";

  const nodeLabel = (n) => {
    const id = n.nodeId;
    const bits = [id, n.type, "status " + statusOf(id)];
    if (finished && statusOf(id) === "PENDING") bits.push("not reached");
    if (activeFail.includes(id)) bits.push("set to fail");
    if (selected === id) bits.push("selected as connection source");
    return bits.join(", ") + ". Press Enter to " + (selected === id ? "deselect" : selected ? "connect from " + selected : "select");
  };

  return (
    <div className="ykp not-prose">
      <style>{CSS}</style>

      <div className="ykp-banner">
        <strong>Simulation.</strong> This runs entirely in your browser to show how Yukta moves a
        workflow through its nodes. No server is contacted and nothing is saved.
      </div>

      {/* Toolbar: preset, add/remove nodes, connection port */}
      <div className="ykp-bar">
        <div className="ykp-field">
          <label className="ykp-label" htmlFor="ykp-preset">Preset</label>
          <select
            id="ykp-preset"
            className="ykp-select"
            value={presetKey}
            onChange={(e) => onPreset(e.target.value)}
          >
            {PRESET_ORDER.map((k) => (
              <option key={k} value={k}>{PRESET_DEFS[k].label}</option>
            ))}
          </select>
        </div>
        <div className="ykp-group">
          <div className="ykp-field">
            <label className="ykp-label" htmlFor="ykp-type">New node type</label>
            <select
              id="ykp-type"
              className="ykp-select"
              value={newType}
              onChange={(e) => setNewType(e.target.value)}
              disabled={!editable}
            >
              {NODE_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <button type="button" className="ykp-btn" onClick={addNode} disabled={!editable}>
            Add node
          </button>
          <button
            type="button"
            className="ykp-btn"
            onClick={removeSelected}
            disabled={!editable || selected === null}
          >
            {selected === null ? "Remove selected" : "Remove " + truncate(selected, 18)}
          </button>
        </div>
        <div className="ykp-field">
          <label className="ykp-label" htmlFor="ykp-port">New edge port</label>
          <select
            id="ykp-port"
            className="ykp-select"
            value={connectPort}
            onChange={(e) => setConnectPort(e.target.value)}
            disabled={!editable}
          >
            <option value="">default (no port)</option>
            <option value="error">error</option>
          </select>
        </div>
      </div>

      <p className={"ykp-hint" + (editable ? "" : " ykp-hint-warn")}>
        {editable ? hint : "Fix the JSON errors below to edit or run. The last valid graph is shown greyed."}
      </p>

      {/* Run controls */}
      <div className="ykp-run">
        <button
          type="button"
          className="ykp-btn ykp-btn-primary"
          onClick={onRun}
          disabled={!editable || playing}
        >
          {finished ? "Run again" : "Run"}
        </button>
        <button type="button" className="ykp-btn" onClick={onStep} disabled={!editable || finished}>
          Step
        </button>
        <button type="button" className="ykp-btn" onClick={onPause} disabled={!playing}>
          Pause
        </button>
        <button type="button" className="ykp-btn" onClick={onReset} disabled={!run}>
          Reset
        </button>
        <span className="ykp-step">step {run ? run.step : 0}</span>
        <span className="ykp-summary">{summarizeRun(graph, run)}</span>
      </div>

      {/* Graph */}
      <div className={"ykp-graph" + (editable ? "" : " ykp-graph-stale")}>
        <svg
          className="ykp-svg"
          viewBox={"0 0 " + layout.width + " " + layout.height}
          style={{ minWidth: Math.round(layout.width * 0.8) + "px" }}
          role="group"
          aria-label="Workflow graph. A table with the same information follows."
        >
          <defs>
            <marker id="ykp-m" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
              <path className="ykp-arr" d="M0,0 L10,5 L0,10 z" />
            </marker>
            <marker id="ykp-m-live" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
              <path className="ykp-arr-live" d="M0,0 L10,5 L0,10 z" />
            </marker>
            <marker id="ykp-m-err" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
              <path className="ykp-arr-err" d="M0,0 L10,5 L0,10 z" />
            </marker>
          </defs>

          <g>
            {graph.edges.map((e, i) => {
              const err = isErrorEdge(e);
              const state = run ? run.edge[i] : "pending";
              const cls =
                "ykp-edge" +
                (err ? " ykp-edge-err" : "") +
                (state === "live" ? " ykp-edge-live" : state === "failed" || state === "idle" ? " ykp-edge-dim" : "");
              const marker = err ? "ykp-m-err" : state === "live" ? "ykp-m-live" : "ykp-m";
              const p = layout.edgePaths[i];
              const label = err ? "error" : e.sourcePort && e.sourcePort !== "default" ? e.sourcePort : null;
              return (
                <g key={i}>
                  <path className={cls} d={p.d} markerEnd={"url(#" + marker + ")"}>
                    <title>{e.source + " to " + e.target + (label ? " on port " + label : "")}</title>
                  </path>
                  {label ? (
                    <text
                      className={"ykp-elabel" + (err ? " ykp-elabel-err" : "")}
                      x={p.lx}
                      y={p.ly - 4}
                      textAnchor="middle"
                    >
                      {label}
                    </text>
                  ) : null}
                </g>
              );
            })}
          </g>

          <g>
            {graph.nodes.map((n) => {
              const id = n.nodeId;
              const p = layout.pos[id];
              const st = statusOf(id);
              const isSel = selected === id;
              const fails = activeFail.includes(id);
              return (
                <g
                  key={id}
                  className={"ykp-node ykp-st-" + st}
                  transform={"translate(" + p.x + "," + p.y + ")"}
                  role="button"
                  tabIndex={editable ? 0 : -1}
                  aria-label={nodeLabel(n)}
                  aria-pressed={isSel}
                  aria-disabled={!editable}
                  onClick={(ev) => activate(id, ev)}
                  onKeyDown={(ev) => {
                    if (ev.key === "Enter" || ev.key === " ") {
                      ev.preventDefault();
                      activate(id, ev);
                    }
                  }}
                >
                  <title>{id + " (" + n.type + "), " + st}</title>
                  {isSel ? (
                    <rect className="ykp-halo" x="-5" y="-5" width={NODE_W + 10} height={NODE_H + 10} rx="16" />
                  ) : null}
                  <rect className="ykp-box" width={NODE_W} height={NODE_H} rx="12" />
                  <text className="ykp-nid" x="12" y="23">{truncate(id, 15)}</text>
                  <text className="ykp-ntype" x="12" y="40">{truncate(n.type || "?", 22)}</text>
                  <StatusIcon status={st} cx={NODE_W - 17} cy={17} />
                  {fails ? (
                    <g aria-hidden="true">
                      <rect className="ykp-flag" x="12" y="-8" width="38" height="15" rx="7.5" />
                      <text className="ykp-flag-text" x="31" y="3" textAnchor="middle">FAIL</text>
                    </g>
                  ) : null}
                </g>
              );
            })}
          </g>

          {/* Messages travelling along the edges that fired in the latest step */}
          {!reduced && run && run.moves.length
            ? run.moves.map((i) => (
                <circle key={run.step + "-" + i} className="ykp-dot" r="5" cx="0" cy="0" aria-hidden="true">
                  <animateMotion dur="0.5s" path={layout.edgePaths[i].d} fill="freeze" />
                  <animate attributeName="opacity" values="1;1;0" keyTimes="0;0.8;1" dur="0.55s" fill="freeze" />
                </circle>
              ))
            : null}
        </svg>
      </div>

      {/* Legend: glyph shape + label, never colour alone */}
      <ul className="ykp-legend" aria-label="Status legend">
        {LEGEND.map(([s, meaning]) => (
          <li key={s}>
            <MiniIcon status={s} />
            <span><span className="ykp-code">{s}</span> {meaning}</span>
          </li>
        ))}
        <li>
          <span className="ykp-swatch ykp-swatch-err" aria-hidden="true" />
          <span><span className="ykp-code">error</span> port, followed when a node fails</span>
        </li>
        <li>
          <span className="ykp-swatch ykp-swatch-live" aria-hidden="true" />
          <span>edge that carried a message</span>
        </li>
      </ul>

      {/* JSON editor */}
      <label className="ykp-h" htmlFor="ykp-json">Workflow JSON</label>
      <textarea
        id="ykp-json"
        className="ykp-textarea"
        value={text}
        onChange={(e) => setText(e.target.value)}
        spellCheck={false}
        autoComplete="off"
        autoCapitalize="off"
        wrap="off"
        aria-invalid={!editable}
        aria-describedby="ykp-json-status"
      />
      <div id="ykp-json-status">
        {editable ? (
          <p className="ykp-ok">
            Valid: {graph.nodes.length} nodes, {graph.edges.length} edges. Paste a full session body
            with "workflows" and the first workflow is used.
          </p>
        ) : (
          <ul className="ykp-errors">
            {errors.slice(0, 8).map((m, i) => (
              <li key={i}>{m}</li>
            ))}
            {errors.length > 8 ? <li>and {errors.length - 8} more.</li> : null}
          </ul>
        )}
      </div>

      {/* Non-visual equivalent of the graph, and a keyboard/touch way to set failures */}
      <span className="ykp-h" id="ykp-details-h">Nodes</span>
      <div className="ykp-tablewrap">
        <table className="ykp-table" aria-labelledby="ykp-details-h">
          <thead>
            <tr>
              <th scope="col">Node</th>
              <th scope="col">Type</th>
              <th scope="col">Status</th>
              <th scope="col">Fail</th>
            </tr>
          </thead>
          <tbody>
            {graph.nodes.map((n) => {
              const id = n.nodeId;
              const st = statusOf(id);
              const fails = activeFail.includes(id);
              const extra =
                finished && st === "PENDING"
                  ? " (not reached)"
                  : run && run.routed.includes(id)
                    ? " (failure routed to the error port)"
                    : "";
              return (
                <tr key={id} className={selected === id ? "ykp-sel-row" : ""}>
                  <td><strong>{id}</strong></td>
                  <td><span className="ykp-code">{n.type}</span></td>
                  <td>
                    <span className="ykp-st-cell">
                      <MiniIcon status={st} />
                      <span><span className="ykp-code">{st}</span>{extra}</span>
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="ykp-btn"
                      aria-pressed={fails}
                      aria-label={"Make " + id + " fail when it runs"}
                      onClick={() => toggleFail(id)}
                      disabled={!editable}
                    >
                      {fails ? "Will fail" : "Fail"}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <p className="ykp-foot">
        Ready to run it for real? Read <a href="/building/defining-a-workflow">Defining a workflow</a>{" "}
        and <a href="/concepts/failure-handling">Failure handling</a>, then follow the{" "}
        <a href="/quickstart">Quickstart</a> to start a server.
      </p>

      {/* One polite live region for step results and edit notices */}
      <div className="ykp-sr" role="status" aria-live="polite" aria-atomic="true">{live}</div>
    </div>
  );
};
