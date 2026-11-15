// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Notifications under load (#2360): OPEN_REQUESTS runs each wait on an ASK_INPUT node (one open
// question each) while people read their inbox and, started by perf/run.sh, stream clients follow
// `/api/notifications/stream` (k6 cannot read a stream: run.sh starts them with curl and measures
// how long each event took to arrive).
//   SCENARIO=steady (default)  ANSWERS answers spread over ANSWER_SECONDS (run.sh: 5 a second for 2 minutes), 10 readers every 5 s
//   SCENARIO=burst             ANSWERS answers within ANSWER_SECONDS (500 in 10 s), 50 readers every second
// Fails when the producer's queue dropped an event (`yukta.notifications.tap.dropped` must stay 0),
// when a notification for an open request is missing from the inbox, or when reading is slow.
// Run: perf/run.sh notifications   (STREAMS=100 SCENARIO=burst perf/run.sh notifications)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { setup, answer, teardown as inputsTeardown } from './inputs.js';

export { setup, answer };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9858';
const API_KEY = __ENV.API_KEY || '';
const SCENARIO = __ENV.SCENARIO || 'steady';
if (!['steady', 'burst'].includes(SCENARIO)) throw new Error(`unknown SCENARIO ${SCENARIO}: steady or burst`);
const OPEN = Number(__ENV.OPEN_REQUESTS || 1000);
const BURST = SCENARIO === 'burst';
// the same variables as inputs.js (whose setup and answer this test reuses); run.sh sets them per scenario
const ANSWERS = Number(__ENV.ANSWERS || 500);
const SECONDS = Number(__ENV.ANSWER_SECONDS || 10);
const READERS = Number(__ENV.READERS || (BURST ? 50 : 10));
const READ_EVERY_S = Number(__ENV.READ_EVERY || (BURST ? 1 : 5));
if (ANSWERS > OPEN) throw new Error(`${ANSWERS} answers need at least that many open requests (OPEN_REQUESTS=${OPEN})`);
const headers = { 'Content-Type': 'application/json' };
if (API_KEY) headers.Authorization = `Bearer ${API_KEY}`;

const inboxMs = new Trend('notifications_read_ms', true);
const summaryMs = new Trend('notifications_summary_ms', true);
const tapDropped = new Gauge('notifications_tap_dropped'); // events the producer's queue lost
const missing = new Counter('notifications_missing'); // open requests without an active ACTION notification

export const options = {
  setupTimeout: '10m',
  teardownTimeout: '5m',
  scenarios: {
    answers: {
      executor: 'constant-arrival-rate', exec: 'answer', rate: Math.ceil(ANSWERS / SECONDS), timeUnit: '1s',
      duration: `${SECONDS}s`, preAllocatedVUs: 50, maxVUs: 200,
    },
    readers: { executor: 'constant-vus', exec: 'read', vus: READERS, duration: `${SECONDS}s` },
  },
  thresholds: {
    input_answers_sent: [`count>=${Math.floor(ANSWERS * 0.99)}`],
    input_answer_ok: ['rate>0.99'],
    input_answer_ms: [BURST ? 'p(95)<1000' : 'p(95)<500'],
    notifications_read_ms: ['p(95)<500'],
    notifications_summary_ms: ['p(95)<500'],
    notifications_tap_dropped: ['value==0'],
    notifications_missing: ['count==0'],
    input_requests_lost: ['count==0'],
    input_stuck_runs: ['count==0'],
    input_heap_used_mb: ['value<1500'],
    dropped_iterations: ['count==0'],
  },
};

export function read() {
  const list = http.get(`${BASE_URL}/api/notifications?size=200`, { headers, tags: { op: 'inbox' } });
  inboxMs.add(list.timings.duration);
  const sum = http.get(`${BASE_URL}/api/notifications/summary`, { headers, tags: { op: 'summary' } });
  summaryMs.add(sum.timings.duration);
  check(list, { 'inbox 200': (r) => r.status === 200 });
  check(sum, { 'summary 200': (r) => r.status === 200 });
  sleep(READ_EVERY_S);
}

const stat = (m, name) => (m.find((x) => x.statistic === name) || {}).value || 0;
const metric = (name, tag) => {
  const r = http.get(`${BASE_URL}/actuator/metrics/${name}${tag ? `?tag=${tag}` : ''}`, { headers });
  return r.status === 200 ? r.json('measurements') : [];
};

export function teardown(ctx) {
  inputsTeardown(ctx);
  tapDropped.add(stat(metric('yukta.notifications.tap.dropped'), 'COUNT'));
  // Every request still open must have its active ACTION notification.
  const active = stat(metric('yukta.notifications.active', 'kind:ACTION'), 'VALUE');
  missing.add(Math.max(0, OPEN - ANSWERS - active));
  console.log(`NOTIFICATIONS scenario=${SCENARIO} active-actions=${active} (want at least ${OPEN - ANSWERS})`);
}
