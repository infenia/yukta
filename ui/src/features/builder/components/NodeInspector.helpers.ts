// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ConfigField } from '../../plugins/types';

/** Engine-level failure policy, available on every node (core `NodePolicy`). */
export const ENGINE_FIELDS: ConfigField[] = [
  {
    name: 'onError',
    type: 'ENUM',
    required: false,
    defaultValue: 'FAIL',
    options: ['FAIL', 'CONTINUE', 'ROUTE'],
    description:
      'What a failing message does (processors). ROUTE sends it to the error port.',
  },
  {
    name: 'trigger',
    type: 'ENUM',
    required: false,
    defaultValue: 'ALL_SUCCESS',
    options: ['ALL_SUCCESS', 'ALL_DONE', 'ONE_SUCCESS'],
    description: 'When a node with several parents starts.',
  },
  {
    name: 'retry',
    type: 'MAP',
    required: false,
    defaultValue: null,
    options: [],
    description:
      '{"maxAttempts": 3, "backoffMs": 200, "maxBackoffMs": 5000} (processors).',
  },
  {
    name: 'elementTimeoutMs',
    type: 'NUMBER',
    required: false,
    defaultValue: null,
    options: [],
    description: 'Longest a processor may stay silent for one message.',
  },
  {
    name: 'deadlineMs',
    type: 'NUMBER',
    required: false,
    defaultValue: null,
    options: [],
    description: 'Longest the node may run in total.',
  },
  {
    name: 'failureBudget',
    type: 'MAP',
    required: false,
    defaultValue: null,
    options: [],
    description:
      '{"maxFailures": 10, "windowMs": 60000}; needs onError CONTINUE or ROUTE.',
  },
];
