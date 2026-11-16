// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { clock, stateWords } from '../agentLabels';
import { createConversation } from '@/test/factories/conversationFactory';

describe('agent labels', () => {
  it('says the state in words, with how an ended agent ended', () => {
    const words = (over: Parameters<typeof createConversation>[0]) =>
      stateWords(createConversation(over));

    expect(words({ state: 'STARTING' })).toBe('Starting');
    expect(words({ state: 'RUNNING' })).toBe('Working');
    expect(words({ state: 'WAITING' })).toBe('Waiting for you');
    expect(words({ state: 'IDLE' })).toBe('Idle');
    expect(words({ state: 'ENDED', outcome: 'success' })).toBe(
      'Ended, succeeded'
    );
    expect(words({ state: 'ENDED', outcome: 'failure' })).toBe('Ended, failed');
    expect(words({ state: 'ENDED', outcome: 'incomplete' })).toBe(
      'Ended, incomplete'
    );
    expect(words({ state: 'ENDED', outcome: null })).toBe('Ended');
  });

  it('shows a time of day, and nothing for a bad one', () => {
    expect(clock('2026-11-16T14:02:00Z')).toMatch(/^\d\d:\d\d$/);
    expect(clock('soon')).toBe('');
  });
});
