// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useAgentStream } from '../useAgentStream';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';
import {
  createConversation,
  createEvent,
} from '@/test/factories/conversationFactory';

describe('useAgentStream', () => {
  beforeEach(installMockEventSource);

  it('does not subscribe without a run', () => {
    const { result } = renderHook(() => useAgentStream(null));

    expect(result.current.conversations).toEqual([]);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('follows the run tree and folds the named events', () => {
    const { result } = renderHook(() => useAgentStream('exec-1'));
    const source = MockEventSource.latest();
    expect(source.url).toBe(
      '/api/executions/exec-1/conversations/stream?tree=true&terminal=false'
    );

    act(() => {
      source.open();
      source.emitNamed(
        'conversation',
        createConversation({ state: 'WAITING' }),
        '1'
      );
      source.emitNamed(
        'event',
        createEvent({ seq: 2, data: { text: 'hi', more: false } }),
        '2'
      );
      source.emitNamed(
        'event',
        createEvent({ seq: 2, data: { text: 'again' } }),
        '2'
      );
      source.emitNamed('other', { x: 1 });
    });

    expect(result.current.status).toBe('open');
    expect(result.current.conversations).toHaveLength(1);
    expect(result.current.waiting).toBe(1);
    expect(result.current.items).toHaveLength(1);
    expect(result.current.items[0]).toMatchObject({ text: 'hi' });
  });

  it('starts empty again for another run', () => {
    const { result, rerender } = renderHook(({ id }) => useAgentStream(id), {
      initialProps: { id: 'exec-1' as string | null },
    });
    act(() =>
      MockEventSource.latest().emitNamed(
        'conversation',
        createConversation(),
        '1'
      )
    );
    expect(result.current.conversations).toHaveLength(1);

    rerender({ id: 'exec-2' });

    expect(result.current.conversations).toEqual([]);
  });
});
