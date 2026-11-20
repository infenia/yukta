// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ConfigField } from '../../plugins/types';

const numberField = (name: string, description: string): ConfigField => ({
  name,
  type: 'NUMBER',
  required: false,
  defaultValue: null,
  options: [],
  description,
});

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
    description: 'Try a failing message again before giving up (processors).',
    fields: [
      numberField('maxAttempts', 'Total attempts, the first one included.'),
      numberField('backoffMs', 'How long to wait before the second attempt.'),
      numberField('maxBackoffMs', 'The longest wait between two attempts.'),
    ],
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
      'Fail the node when too many messages fail; needs On error to be CONTINUE or ROUTE.',
    fields: [
      numberField(
        'maxFailures',
        'Failures allowed within the window; one more fails the node.'
      ),
      numberField('windowMs', 'How long the window is.'),
    ],
  },
];
