// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { CATALOG, selected, sessionBody, sessionId } from './workflows.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || '';
// Never send the key in clear text to a remote host.
if (API_KEY && !/^https:\/\//i.test(BASE_URL) && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/|$)/i.test(BASE_URL)) {
  throw new Error('API_KEY requires an https BASE_URL (or localhost)');
}
const POLL_OK = http.expectedStatuses(200, 404); // 404 right after start is counted in poll_not_found
const STOP_OK = http.expectedStatuses({ min: 200, max: 499 }); // the run may have ended already
const START_OK = http.expectedStatuses(202, 429); // 429 is admission control, judged by workflow_rejected
// LENIENT=true treats a run as done once every non-terminal node has ended, which hides runs
// whose own status never turns final. Off by default; stuck_runs (teardown) also catches them.
const LENIENT = (__ENV.LENIENT || 'false') === 'true';
const GRACE_S = Number(__ENV.GRACE || 30); // how long teardown waits for runs to finish
const FINAL = ['SUCCESS', 'FAILURE', 'ERROR', 'STOPPED', 'CANCELLED', 'TIMED_OUT',
  'SKIPPED', 'UPSTREAM_FAILED', 'ABORTED'];

export const e2e = new Trend('workflow_e2e', true);
export const success = new Rate('workflow_success');
export const rejected = new Rate('workflow_rejected'); // share of starts refused with HTTP 429
export const lenientDone = new Counter('workflow_lenient_done');
export const pollNotFound = new Counter('poll_not_found'); // run not queryable yet after its 202
export const stuckRuns = new Counter('stuck_runs'); // runs still not final after GRACE
export const teardownErrors = new Counter('teardown_errors'); // history lookups of teardown that did not answer 200
export const trackerLost = new Counter('tracker_events_lost'); // state events the server dropped

export const headers = { 'Content-Type': 'application/json' };
if (API_KEY) headers.Authorization = `Bearer ${API_KEY}`;

// Value of an actuator metric, 0 if it was never recorded (404) or cannot be read.
function metric(name, tag) {
  const r = http.get(`${BASE_URL}/actuator/metrics/${name}${tag ? `?tag=${tag}` : ''}`, { headers });
  return r.status === 200 ? Number(r.json('measurements.0.value')) || 0 : 0;
}
const lostNow = () => metric('yukta.tracker.dropped', 'type:state');

// Register one session per selected workflow.
export function setup() {
  const names = selected();
  for (const name of names) {
    const r = http.post(`${BASE_URL}/api/sessions`, JSON.stringify(sessionBody(name)), { headers });
    if (r.status !== 200) throw new Error(`setup '${name}' failed: ${r.status} ${r.body}`);
  }
  return { names, lost0: lostNow() };
}

// Runs of the test sessions whose own status is still RUNNING after the grace period are stuck.
export function teardown(ctx) {
  const deadline = Date.now() + GRACE_S * 1000;
  let stuck = {};
  do {
    stuck = {};
    for (const name of ctx.names) {
      let cursor = '';
      do {
        const r = http.get(`${BASE_URL}/api/workflow/${sessionId(name)}/history?status=RUNNING&limit=1000${cursor}`, { headers });
        if (r.status !== 200) {
          // not knowing is not the same as nothing being stuck: count it, or stuck_runs passes without looking
          teardownErrors.add(1);
          console.error(`TEARDOWN ${name}: history answered ${r.status}`);
          break;
        }
        (stuck[name] = stuck[name] || []).push(...r.json('data').map((x) => x.executionId));
        const next = r.headers['X-Next-Cursor'];
        cursor = next ? `&cursor=${next}` : '';
      } while (cursor);
    }
    if (!Object.keys(stuck).length) break;
    sleep(2);
  } while (Date.now() < deadline);
  const n = Object.values(stuck).reduce((a, ids) => a + ids.length, 0);
  stuckRuns.add(n);
  for (const [name, ids] of Object.entries(stuck)) console.error(`STUCK ${name}: ${ids.length} e.g. ${ids.slice(0, 3)}`);
  trackerLost.add(lostNow() - ctx.lost0);
}

// Returns 'ok' | 'failed' | null (still running)
function outcome(data) {
  if (FINAL.includes(data.status)) return data.status === 'SUCCESS' ? 'ok' : 'failed';
  if (!LENIENT || !data.tasks.length) return null;
  const nodes = data.tasks.filter((t) => t.module !== 'CONSOLE_TERMINAL');
  if (!nodes.every((t) => FINAL.includes(t.status))) return null;
  lenientDone.add(1);
  return nodes.every((t) => t.status === 'SUCCESS') ? 'ok' : 'failed';
}

export function runOnce(ctx) {
  // round-robin: deterministic and even across VUs
  const name = ctx.names[exec.scenario.iterationInTest % ctx.names.length];
  const tags = { wf: name };
  const start = Date.now();
  const body = { sessionId: sessionId(name), workflowId: 'wf' };
  const w = CATALOG[name];
  if (w.payload) body.payload = typeof w.payload === 'function' ? w.payload() : w.payload;
  const r = http.post(`${BASE_URL}/api/workflow/start`, JSON.stringify(body), { headers, tags, responseCallback: START_OK });
  rejected.add(r.status === 429, tags);
  if (r.status === 429) return;
  if (!check(r, { 'start 202': (x) => x.status === 202 }, tags)) { success.add(false, tags); return; }
  const id = r.json('data.executionId');
  if (w.cancelAfterMs && Math.random() < 0.5) {
    sleep(w.cancelAfterMs / 1000);
    http.post(`${BASE_URL}/api/executions/${id}/stop`, null, { headers, tags, responseCallback: STOP_OK });
  }
  for (let i = 0; i < 100; i++) {
    const s = http.get(`${BASE_URL}/api/executions/${id}`, { headers, tags, responseCallback: POLL_OK });
    if (s.status === 404) pollNotFound.add(1, tags);
    const res = s.status === 200 ? outcome(s.json('data')) : null;
    if (res) {
      e2e.add(Date.now() - start, tags);
      success.add(w.chaos || res === 'ok', tags); // a chaos run may legitimately fail or be cancelled
      return;
    }
    sleep(0.05);
  }
  success.add(false, tags); // never finished
}

export const thresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500'],
  workflow_e2e: ['p(95)<2000'],
  workflow_success: ['rate>0.99'],
  workflow_rejected: [`rate<${__ENV.MAX_REJECTED || 0.01}`],
  stuck_runs: ['count==0'], // a run that never reached a final status
  teardown_errors: ['count==0'], // stuck_runs could not look: the history endpoint did not answer 200
  tracker_events_lost: ['count==0'], // server dropped a status event
  dropped_iterations: ['count==0'], // generator ran out of VUs: results understate the load
};
// Per-workflow breakdown in the summary, so a failing plugin is named.
for (const n of selected()) {
  thresholds[`workflow_success{wf:${n}}`] = ['rate>0.99'];
  thresholds[`workflow_e2e{wf:${n}}`] = ['p(95)<2000'];
}
