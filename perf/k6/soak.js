// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { BASE_URL, headers, setup, teardown, runOnce, thresholds } from './lib.js';

export { setup, teardown };

const duration = __ENV.DURATION || '1h';
const samplerErrors = new Counter('sampler_errors');
const jvm = {
  'jvm.memory.used': new Trend('jvm_memory_used'),
  'jvm.threads.live': new Trend('jvm_threads_live'),
  'process.cpu.usage': new Trend('process_cpu_usage'),
};
// growth since the first sample after the warm-up; the last value is checked, so a leak fails the soak
const WARMUP_S = Number(__ENV.SOAK_WARMUP_S || 300);
const growth = {
  'jvm.memory.used': { gauge: new Gauge('jvm_memory_growth_mb'), scale: 1 / (1024 * 1024) },
  'jvm.threads.live': { gauge: new Gauge('jvm_threads_growth'), scale: 1 },
};
const baseline = {};
const started = Date.now();

export const options = {
  teardownTimeout: '5m',
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20), timeUnit: '1s',
      duration, preAllocatedVUs: 20, maxVUs: 200, exec: 'work',
    },
    sampler: { executor: 'constant-vus', vus: 1, duration, exec: 'sample' },
  },
  thresholds: {
    ...thresholds,
    sampler_errors: ['count==0'],
    jvm_memory_growth_mb: [`value<${Number(__ENV.SOAK_MAX_HEAP_GROWTH_MB || 512)}`],
    jvm_threads_growth: [`value<${Number(__ENV.SOAK_MAX_THREAD_GROWTH || 50)}`],
  },
};

export function work(ctx) { runOnce(ctx); }

// Needs /actuator/metrics exposed (on by default, also in the prod profile).
export function sample() {
  for (const [name, trend] of Object.entries(jvm)) {
    const r = http.get(`${BASE_URL}/actuator/metrics/${name}`, { headers });
    if (r.status !== 200) {
      samplerErrors.add(1);
      continue;
    }
    const value = r.json('measurements.0.value');
    trend.add(value);
    const g = growth[name];
    if (g && Date.now() - started >= WARMUP_S * 1000) {
      if (baseline[name] === undefined) baseline[name] = value;
      g.gauge.add((value - baseline[name]) * g.scale);
    }
  }
  sleep(30);
}
