// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { ExecutionResultBlock } from '../ExecutionResultBlock';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';

describe('ExecutionResultBlock', () => {
  beforeEach(() => installMockEventSource());

  it('renders nothing without an execution', () => {
    render(<ExecutionResultBlock sessionId="s1" executionId={null} />);

    expect(screen.queryByTestId('execution-result')).not.toBeInTheDocument();
  });

  it('waits, then shows string and structured results as they arrive', () => {
    render(<ExecutionResultBlock sessionId="s1" executionId="e1" />);
    expect(screen.getByText(/Waiting for a result/)).toBeInTheDocument();

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit({
        kind: 'RESULT',
        nodeId: 'done',
        sequence: 0,
        payload: 'plain answer',
        status: null,
        truncated: false,
        timestamp: '2026-01-01T00:00:00Z',
      });
      MockEventSource.latest().emit({
        kind: 'RESULT',
        nodeId: 'done',
        sequence: 1,
        payload: { total: 3 },
        status: null,
        truncated: false,
        timestamp: '2026-01-01T00:00:01Z',
      });
    });

    expect(screen.getByText('plain answer')).toBeInTheDocument();
    expect(screen.getByText(/"total": 3/)).toBeInTheDocument();
    expect(screen.getByTestId('result-streaming')).toBeInTheDocument();
  });

  it('says so when the execution ended without a result', () => {
    render(<ExecutionResultBlock sessionId="s1" executionId="e1" />);

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit({
        kind: 'EXECUTION_END',
        nodeId: null,
        sequence: 0,
        payload: null,
        status: 'SUCCESS',
        truncated: false,
        timestamp: '2026-01-01T00:00:02Z',
      });
    });

    expect(
      screen.getByText('The execution produced no result.')
    ).toBeInTheDocument();
  });

  it('shows an empty line for a result without a payload', () => {
    render(<ExecutionResultBlock sessionId="s1" executionId="e1" />);

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit({
        kind: 'RESULT',
        nodeId: 'done',
        sequence: 0,
        payload: undefined,
        status: null,
        truncated: false,
        timestamp: '2026-01-01T00:00:00Z',
      });
    });

    expect(screen.getByTestId('execution-result')).toBeInTheDocument();
    expect(screen.queryByText(/Waiting for a result/)).not.toBeInTheDocument();
  });
});
