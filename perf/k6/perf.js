// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Baseline latency: a fixed, moderate rate so runs on different commits can be compared.
// Run with --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" (perf/run.sh does).
import { setup, teardown, runOnce, thresholds } from './lib.js';

export { setup, teardown };

export const options = {
  teardownTimeout: '5m',
  scenarios: {
    perf: {
      executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 10), timeUnit: '1s',
      duration: __ENV.DURATION || '2m', preAllocatedVUs: 20, maxVUs: 200,
    },
  },
  thresholds,
};

export default runOnce;
