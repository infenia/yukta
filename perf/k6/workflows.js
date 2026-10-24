// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Catalog of workflows the tests can drive. Select with -e WORKFLOWS=a,b (default: DEFAULT).
// Each entry: nodes, edges and the optional start payload. To test another plugin, add an entry.

const T = (id) => ({ nodeId: id, type: 'CONSTANT_SOURCE', config: {} });
const API = (id) => ({ nodeId: id, type: 'API_TRIGGER', config: {} });
const MAP = (id) => ({ nodeId: id, type: 'MAPPER', config: { mode: 'TEMPLATE', mapping: { x: 'hi' } } });
const OUT = (id) => ({ nodeId: id, type: 'CONSOLE_TERMINAL', config: {} });
const chain = (...ids) => ids.slice(1).map((target, i) => ({ source: ids[i], target }));

const DELAY = (id, ms) => ({ nodeId: id, type: 'DELAY', config: { delayMs: ms } });
// a strict SPLITTER whose header mapping 10 / payload.d fails the node with "/ by zero" when d is 0
const DIV = (id, engine) => ({ nodeId: id, type: 'SPLITTER',
  config: { itemsPath: 'payload.items', headerMapping: { q: '10 / payload.d' }, strictMode: true,
            ...(engine && { engine }) } });
const flaky = () => ({ items: [1], d: Math.random() < 0.3 ? 0 : 1 }); // 30% of the runs fail

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
  // Chaos: the run may end FAILURE or CANCELLED; the checks only require that it reaches a final status
  // (workflow_success counts that for these) so stuck_runs covers the error paths.
  // fails 30% of the runs
  'chaos-fail': { chaos: true, nodes: [API('t'), DIV('m'), OUT('c')], edges: chain('t', 'm', 'c'), payload: flaky },
  // the same failure retried (3 attempts), then the message is dropped
  'chaos-retry': {
    chaos: true, payload: flaky, edges: chain('t', 'm', 'c'),
    nodes: [API('t'), DIV('m', { retry: { maxAttempts: 3, backoffMs: 20 }, onError: 'CONTINUE' }), OUT('c')],
  },
  // half of the runs are cancelled 100 ms after they start, while the DELAY holds them
  'chaos-cancel': {
    chaos: true, cancelAfterMs: 100,
    nodes: [T('t'), DELAY('d', 300), MAP('m'), OUT('c')], edges: chain('t', 'd', 'm', 'c'),
  },
  // spawns an OS process: heavy, opt-in only
  process: {
    nodes: [T('t'), { nodeId: 'p', type: 'PROCESS_EXECUTOR',
      config: { command: ['echo', 'hi'], workingDir: '/tmp', timeout: 10, streamOutput: false,
                outputTarget: 'PAYLOAD' } }, OUT('c')],
    edges: chain('t', 'p', 'c'),
  },
};

export const DEFAULT = ['trigger-terminal', 'mapper', 'filter', 'chain10', 'fanout',
  'chaos-fail', 'chaos-retry', 'chaos-cancel'];

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
