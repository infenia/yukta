// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import type { Me } from '@/lib/identity';
import type { InputAnswer, InputRequest } from '../../api/inputs';
import {
  askingNodes,
  deadlineShare,
  outcome,
  responderLabel,
  tierLabel,
} from '../inputNodes';

const req = (over: Partial<InputRequest> = {}) =>
  ({
    state: 'ANSWERED',
    answer: null,
    createdAt: '2026-01-01T00:00:00Z',
    deadline: '2026-01-01T00:01:40Z',
    ...over,
  }) as InputRequest;
const answer = (over: Partial<InputAnswer> = {}) =>
  ({
    decision: 'x',
    responderType: 'HUMAN',
    responderId: 'bob',
    ...over,
  }) as InputAnswer;
const at = (s: number) => Date.parse('2026-01-01T00:00:00Z') + s * 1000;

describe('askingNodes', () => {
  it('keeps the plugins that can ask, with what each does', () => {
    const nodes = [
      { nodeId: 'a', type: 'ASK_INPUT' },
      { nodeId: 'b', type: 'LLM_CALL' },
      { nodeId: 'c', type: 'WAIT_FOR_SIGNAL' },
      { nodeId: 'd', type: 'AGENT_SESSION' },
      { nodeId: 'e', type: 'MCP_TOOL_CALL' },
    ];
    expect(askingNodes(nodes).map((n) => [n.nodeId, n.label])).toEqual([
      ['a', 'Asks a question'],
      ['c', 'Waits for approval'],
      ['d', 'Agent asks before using a tool'],
      ['e', 'May ask before a tool call'],
    ]);
    expect(askingNodes([])).toEqual([]);
  });
});

describe('outcome', () => {
  it('names every way a request ends', () => {
    expect(outcome(req({ state: 'EXPIRED' })).label).toBe('Expired');
    expect(outcome(req({ state: 'CANCELLED' })).tone).toBe('muted');
    expect(outcome(req({ answer: answer({ decision: 'reject' }) })).label).toBe(
      'Rejected'
    );
    expect(
      outcome(req({ answer: answer({ decision: 'approve' }) })).label
    ).toBe('Approved');
    expect(outcome(req({ answer: answer({ decision: 'blue' }) })).label).toBe(
      'Answered'
    );
    expect(outcome(req()).label).toBe('Answered');
  });
});

describe('responderLabel', () => {
  const me = { keyName: 'bob' } as Me;
  it('says who answered', () => {
    expect(responderLabel(answer(), me)).toBe('You');
    expect(responderLabel(answer(), null)).toBe('bob');
    expect(responderLabel(answer(), { keyName: null } as Me)).toBe('bob');
    expect(responderLabel(answer({ responderId: 'anonymous' }), me)).toBe(
      'Anonymous (no API key)'
    );
    expect(
      responderLabel(answer({ responderType: 'AUTO', responderId: 'g' }), me)
    ).toBe('g (supervisor)');
  });
});

describe('tierLabel', () => {
  it('phrases the tier', () => {
    expect(tierLabel('default')).toBe('anyone allowed to answer');
    expect(tierLabel('leads')).toBe('the leads tier');
  });
});

describe('deadlineShare', () => {
  it('is the share of the time left, from creation or the last step', () => {
    expect(deadlineShare(req(), at(25))).toBeCloseTo(0.75);
    expect(deadlineShare(req(), at(-10))).toBe(1);
    expect(deadlineShare(req(), at(200))).toBe(0);
    expect(
      deadlineShare(
        req({
          asked: [
            { who: 'a', said: 'x', reason: null, at: '2026-01-01T00:00:50Z' },
          ],
        }),
        at(75)
      )
    ).toBeCloseTo(0.5);
  });
  it('is full without a deadline and empty for a deadline before the start', () => {
    expect(deadlineShare(req({ deadline: null }), at(5))).toBe(1);
    expect(
      deadlineShare(req({ deadline: '2025-01-01T00:00:00Z' }), at(5))
    ).toBe(0);
    expect(deadlineShare(req({ createdAt: 'bad' }), at(5))).toBe(0);
  });
});
