// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Notifications under load (#2360): the input requests test (1,000 open requests, 500 answers in 10 s)
// while people read their inbox (`/api/notifications`, `/summary`) and, started by perf/run.sh, 200
// stream clients follow `/api/notifications/stream`. Fails when the producer's queue dropped an event
// (`yukta.notifications.tap.dropped` must stay 0), when a notification for an open request is missing
// from the inbox, or when reading the inbox is slow.
// Run: perf/run.sh notifications   (k6 cannot read a stream: run.sh starts the stream clients with curl)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { setup, answer, teardown as inputsTeardown } from './inputs.js';

export { setup, answer };

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9858';
const API_KEY = __ENV.API_KEY || '';
const OPEN = Number(__ENV.OPEN_REQUESTS || 1000);
const ANSWERS = Number(__ENV.ANSWERS || 500);
const ANSWER_SECONDS = Number(__ENV.ANSWER_SECONDS || 10);
const READERS = Number(__ENV.READERS || 50);
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
      executor: 'constant-arrival-rate', exec: 'answer', rate: Math.ceil(ANSWERS / ANSWER_SECONDS), timeUnit: '1s',
      duration: `${ANSWER_SECONDS}s`, preAllocatedVUs: 50, maxVUs: 200,
    },
    readers: { executor: 'constant-vus', exec: 'read', vus: READERS, duration: `${ANSWER_SECONDS}s` },
  },
  thresholds: {
    input_answers_sent: [`count>=${Math.floor(ANSWERS * 0.99)}`],
    input_answer_ok: ['rate>0.99'],
    input_answer_ms: ['p(95)<500'],
    notifications_read_ms: ['p(95)<1000'],
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
  sleep(1);
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
  console.log(`NOTIFICATIONS active-actions=${active} (want at least ${OPEN - ANSWERS})`);
}
