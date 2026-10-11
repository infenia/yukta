// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import http from 'k6/http';
import { sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, setup, runOnce, thresholds } from './lib.js';

export { setup };

const duration = __ENV.DURATION || '1h';
const jvm = {
  'jvm.memory.used': new Trend('jvm_memory_used'),
  'jvm.threads.live': new Trend('jvm_threads_live'),
  'process.cpu.usage': new Trend('process_cpu_usage'),
};

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20), timeUnit: '1s',
      duration, preAllocatedVUs: 20, maxVUs: 200, exec: 'work',
    },
    sampler: { executor: 'constant-vus', vus: 1, duration, exec: 'sample' },
  },
  thresholds,
};

export function work(ctx) { runOnce(ctx); }

// Needs /actuator/metrics exposed (on by default, also in the prod profile).
export function sample() {
  for (const [name, trend] of Object.entries(jvm)) {
    const r = http.get(`${BASE_URL}/actuator/metrics/${name}`);
    if (r.status === 200) trend.add(r.json('measurements.0.value'));
  }
  sleep(30);
}
