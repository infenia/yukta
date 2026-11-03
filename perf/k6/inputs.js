// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Input requests under load (#1800): OPEN_REQUESTS runs (default 1000, spread over SESSIONS sessions so
// the per-session cap of 50 open requests holds) each wait on an ASK_INPUT node; then, at the same time,
// ANSWERS answers (default 500) arrive within ANSWER_SECONDS (default 10) while READERS (default 50)
// clients read the open requests once a second, as supervisors that list instead of following the feed do.
// Records the answer latency, the read latency and the heap, and fails when an answer is refused, a run
// does not finish, or a request is lost.
// Run: perf/run.sh would need its own entry; against a running server:
//   k6 run -e BASE_URL=http://localhost:9858 -e API_KEY=... perf/k6/inputs.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9858';
const API_KEY = __ENV.API_KEY || '';
if (API_KEY && !/^https:\/\//i.test(BASE_URL) && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/|$)/i.test(BASE_URL)) {
  throw new Error('API_KEY requires an https BASE_URL (or localhost)');
}
const OPEN = Number(__ENV.OPEN_REQUESTS || 1000);
const ANSWERS = Number(__ENV.ANSWERS || 500);
const ANSWER_SECONDS = Number(__ENV.ANSWER_SECONDS || 10);
const READERS = Number(__ENV.READERS || 50);
const SESSIONS = Number(__ENV.SESSIONS || 25);
const GRACE_S = Number(__ENV.GRACE || 60);
if (OPEN / SESSIONS > 50) throw new Error('OPEN_REQUESTS / SESSIONS is over the cap of 50 open requests per session');

const headers = { 'Content-Type': 'application/json' };
if (API_KEY) headers.Authorization = `Bearer ${API_KEY}`;
const sid = (i) => `k6-inputs-${i}`;

export const answerMs = new Trend('input_answer_ms', true);
export const answerOk = new Rate('input_answer_ok');
export const readMs = new Trend('input_read_ms', true);
export const sweepMs = new Gauge('input_sweep_mean_ms'); // yukta.input.sweep: one deadline sweep, mean
export const answersSent = new Counter('input_answers_sent');
export const heapMb = new Gauge('input_heap_used_mb');
export const lostRequests = new Counter('input_requests_lost'); // asked but never listed as open
export const stuckRuns = new Counter('input_stuck_runs'); // answered, but the run did not end

const WORKFLOW = {
  workflowId: 'wf', description: 'asks and waits',
  nodes: [
    { nodeId: 't', type: 'CONSTANT_SOURCE', config: {} },
    { nodeId: 'q', type: 'ASK_INPUT', config: { prompt: 'Go?', kind: 'APPROVAL', risk: 'LOW', ttlSeconds: 3600 } },
    { nodeId: 'c', type: 'CONSOLE_TERMINAL', config: {} },
  ],
  edges: [{ source: 't', target: 'q' }, { source: 'q', target: 'c' }],
};

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '5m',
  scenarios: {
    answers: {
      executor: 'constant-arrival-rate', exec: 'answer', rate: Math.ceil(ANSWERS / ANSWER_SECONDS), timeUnit: '1s',
      duration: `${ANSWER_SECONDS}s`, preAllocatedVUs: 50, maxVUs: 200,
    },
    readers: {
      executor: 'constant-vus', exec: 'read', vus: READERS, duration: `${ANSWER_SECONDS}s`,
    },
  },
  thresholds: {
    input_answers_sent: [`count>=${Math.floor(ANSWERS * 0.99)}`], // a test that sent nothing must not pass
    input_answer_ok: ['rate>0.99'],
    input_answer_ms: ['p(95)<500'],
    input_read_ms: ['p(95)<1000'],
    input_requests_lost: ['count==0'],
    input_stuck_runs: ['count==0'],
    input_sweep_mean_ms: ['value<250'], // with 1,000 open requests a sweep reads them all (in memory)
    input_heap_used_mb: ['value<1500'], // 1,000 open requests are small; a leak is not
    dropped_iterations: ['count==0'],
  },
};

function metric(name, tag) {
  const r = http.get(`${BASE_URL}/actuator/metrics/${name}${tag ? `?tag=${tag}` : ''}`, { headers });
  return r.status === 200 ? r.json('measurements') : [];
}
const stat = (m, name) => (m.find((x) => x.statistic === name) || {}).value || 0;

// Starts the runs and waits until every one of them has an open request.
export function setup() {
  for (let i = 0; i < SESSIONS; i++) {
    const r = http.post(`${BASE_URL}/api/sessions`, JSON.stringify({
      sessionId: sid(i), name: sid(i), description: 'k6 inputs', initiator: 'k6', projectPath: '/tmp', tags: {},
      workflows: { wf: WORKFLOW },
    }), { headers });
    if (r.status !== 200) throw new Error(`session ${i}: ${r.status} ${r.body}`);
  }
  for (let n = 0; n < OPEN; n++) {
    const r = http.post(`${BASE_URL}/api/workflow/start`, JSON.stringify({ sessionId: sid(n % SESSIONS), workflowId: 'wf' }), { headers });
    if (r.status !== 202) throw new Error(`start ${n}: ${r.status} ${r.body}`);
  }
  const deadline = Date.now() + 5 * 60 * 1000;
  let open = [];
  while (Date.now() < deadline) {
    open = [];
    for (let i = 0; i < SESSIONS; i++) {
      const r = http.get(`${BASE_URL}/api/sessions/${sid(i)}/inputs?limit=1000`, { headers });
      if (r.status === 200) open.push(...r.json('data').map((x) => ({ e: x.executionId, r: x.id })));
    }
    if (open.length >= OPEN) break;
    sleep(1);
  }
  if (open.length < OPEN) throw new Error(`only ${open.length} of ${OPEN} requests are open after 5 minutes`);
  return { answers: open.slice(0, ANSWERS) };
}

export function answer(ctx) {
  const target = ctx.answers[exec.scenario.iterationInTest];
  const r = http.post(`${BASE_URL}/api/executions/${target.e}/inputs/${target.r}/answer`,
    JSON.stringify({ decision: 'approve', rationale: 'k6' }), { headers, tags: { op: 'answer' } });
  answerMs.add(r.timings.duration);
  answersSent.add(1);
  answerOk.add(check(r, { 'answer 200': (x) => x.status === 200 }));
}

export function read() {
  const r = http.get(`${BASE_URL}/api/inputs?limit=1000`, { headers, tags: { op: 'read' } });
  readMs.add(r.timings.duration);
  check(r, { 'read 200': (x) => x.status === 200 });
  sleep(1);
}

// The unanswered requests are still open; the answered runs have ended. Then the server's own numbers.
export function teardown(ctx) {
  const deadline = Date.now() + GRACE_S * 1000;
  let stuck = ANSWERS;
  while (Date.now() < deadline) {
    stuck = 0;
    for (const t of ctx.answers.slice(0, 50)) { // a sample is enough: the answers are not ordered by run
      const s = http.get(`${BASE_URL}/api/executions/${t.e}`, { headers });
      if (s.status !== 200 || s.json('data.status') === 'RUNNING') stuck++;
    }
    if (!stuck) break;
    sleep(2);
  }
  stuckRuns.add(stuck);
  let open = 0;
  for (let i = 0; i < SESSIONS; i++) {
    const r = http.get(`${BASE_URL}/api/sessions/${sid(i)}/inputs?limit=1000`, { headers });
    if (r.status === 200) open += r.json('data').length;
  }
  lostRequests.add(Math.max(0, OPEN - ANSWERS - open));
  const sweep = metric('yukta.input.sweep', '');
  if (stat(sweep, 'COUNT')) sweepMs.add((stat(sweep, 'TOTAL_TIME') / stat(sweep, 'COUNT')) * 1000);
  heapMb.add(stat(metric('jvm.memory.used', 'area:heap'), 'VALUE') / 1048576);
  console.log(`INPUTS open=${open} (want ${OPEN - ANSWERS}) answered-runs-not-ended=${stuck}`);
}
