// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { setup, runOnce, thresholds } from './lib.js';

export { setup };

const rate = Number(__ENV.RATE || 100);

export const options = {
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
