// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { setup, teardown, runOnce, thresholds } from './lib.js';

export { setup, teardown };

const rate = Number(__ENV.RATE || 100);

export const options = {
  teardownTimeout: '5m',
  scenarios: {
    load: {
      executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s',
      preAllocatedVUs: 50, maxVUs: 500,
      stages: [
        { target: rate, duration: __ENV.RAMP || '1m' },
        { target: rate, duration: __ENV.HOLD || '3m' },
        { target: 0, duration: __ENV.RAMP || '1m' },
      ],
    },
  },
  thresholds,
};

export default runOnce;
