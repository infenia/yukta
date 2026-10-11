// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Catalog of workflows the tests can drive. Select with -e WORKFLOWS=a,b (default: DEFAULT).
// Each entry: nodes, edges and the optional start payload. To test another plugin, add an entry.

const T = (id) => ({ nodeId: id, type: 'CONSTANT_SOURCE', config: {} });
const API = (id) => ({ nodeId: id, type: 'API_TRIGGER', config: {} });
const MAP = (id) => ({ nodeId: id, type: 'MAPPER', config: { mode: 'TEMPLATE', mapping: { x: 'hi' } } });
const OUT = (id) => ({ nodeId: id, type: 'CONSOLE_TERMINAL', config: {} });
const chain = (...ids) => ids.slice(1).map((target, i) => ({ source: ids[i], target }));

const ids = (n) => Array.from({ length: n }, (_, i) => `m${i}`);

export const CATALOG = {
  // trigger -> terminal: the cheapest possible run, measures engine/admission overhead
  'trigger-terminal': { nodes: [T('t'), OUT('c')], edges: chain('t', 'c') },
  // API_TRIGGER carrying the start payload through a MAPPER
  mapper: { nodes: [API('t'), MAP('m'), OUT('c')], edges: chain('t', 'm', 'c'), payload: { n: 1 } },
  // FILTER with a SpEL condition
  filter: {
    nodes: [API('t'), { nodeId: 'f', type: 'FILTER', config: { condition: 'true' } }, OUT('c')],
    edges: chain('t', 'f', 'c'), payload: { n: 1 },
  },
  // two parallel branches merging into one terminal
  fanout: {
    nodes: [T('t'), MAP('a'), MAP('b'), OUT('c')],
    edges: [{ source: 't', target: 'a' }, { source: 't', target: 'b' },
            { source: 'a', target: 'c' }, { source: 'b', target: 'c' }],
  },
  // 10 processors in a row: stresses per-node scheduling and checkpoints
  chain10: {
    nodes: [T('t'), ...ids(10).map(MAP), OUT('c')], edges: chain('t', ...ids(10), 'c'),
  },
  // spawns an OS process: heavy, opt-in only
  process: {
    nodes: [T('t'), { nodeId: 'p', type: 'PROCESS_EXECUTOR',
      config: { command: ['echo', 'hi'], workingDir: '/tmp', timeout: 10, streamOutput: false,
                outputTarget: 'PAYLOAD' } }, OUT('c')],
    edges: chain('t', 'p', 'c'),
  },
};

// fanout is left out until perf/KNOWN-ISSUES.md #2 is fixed; run it with -e WORKFLOWS=fanout.
export const DEFAULT = ['trigger-terminal', 'mapper', 'filter', 'chain10'];

export function selected() {
  const names = (__ENV.WORKFLOWS ? __ENV.WORKFLOWS.split(',') : DEFAULT).map((s) => s.trim());
  for (const n of names) if (!CATALOG[n]) throw new Error(`unknown workflow '${n}'`);
  return names;
}

export const sessionId = (name) => `k6-${name}`;

export function sessionBody(name) {
  const w = CATALOG[name];
  return {
    sessionId: sessionId(name), name: `k6-${name}`, description: 'k6 perf', initiator: 'k6',
    projectPath: '/tmp', tags: {},
    workflows: { wf: { workflowId: 'wf', description: name, nodes: w.nodes, edges: w.edges } },
  };
}
