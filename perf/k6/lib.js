// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { CATALOG, selected, sessionBody, sessionId } from './workflows.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || '';
// Known issue (perf/KNOWN-ISSUES.md #1): CONSOLE_TERMINAL never leaves RUNNING, so a run never
// ends. LENIENT=true (default) treats a run as done once every non-terminal node has ended.
// Set -e LENIENT=false once that is fixed, to require the run itself to reach a final status.
const LENIENT = (__ENV.LENIENT || 'true') !== 'false';
const FINAL = ['SUCCESS', 'FAILURE', 'ERROR', 'STOPPED', 'CANCELLED', 'TIMED_OUT',
  'SKIPPED', 'UPSTREAM_FAILED', 'ABORTED'];

export const e2e = new Trend('workflow_e2e', true);
export const success = new Rate('workflow_success');
export const rejected = new Counter('workflow_rejected');
export const lenientDone = new Counter('workflow_lenient_done');

const headers = { 'Content-Type': 'application/json' };
if (API_KEY) headers.Authorization = `Bearer ${API_KEY}`;

// Register one session per selected workflow.
export function setup() {
  const names = selected();
  for (const name of names) {
    const r = http.post(`${BASE_URL}/api/sessions`, JSON.stringify(sessionBody(name)), { headers });
    if (r.status !== 200) throw new Error(`setup '${name}' failed: ${r.status} ${r.body}`);
  }
  return { names };
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
  const name = ctx.names[Math.floor(Math.random() * ctx.names.length)];
  const tags = { wf: name };
  const start = Date.now();
  const body = { sessionId: sessionId(name), workflowId: 'wf' };
  if (CATALOG[name].payload) body.payload = CATALOG[name].payload;
  const r = http.post(`${BASE_URL}/api/workflow/start`, JSON.stringify(body), { headers, tags });
  if (r.status === 429) { rejected.add(1, tags); return; }
  if (!check(r, { 'start 202': (x) => x.status === 202 }, tags)) { success.add(false, tags); return; }
  const id = r.json('data.executionId');
  for (let i = 0; i < 100; i++) {
    const s = http.get(`${BASE_URL}/api/executions/${id}`, { headers, tags });
    const res = s.status === 200 ? outcome(s.json('data')) : null;
    if (res) {
      e2e.add(Date.now() - start, tags);
      success.add(res === 'ok', tags);
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
};
// Per-workflow breakdown in the summary, so a failing plugin is named.
for (const n of selected()) {
  thresholds[`workflow_success{wf:${n}}`] = ['rate>0.99'];
  thresholds[`workflow_e2e{wf:${n}}`] = ['p(95)<2000'];
}
