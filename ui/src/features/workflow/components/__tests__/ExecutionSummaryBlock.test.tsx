// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ExecutionSummaryBlock } from '../ExecutionSummaryBlock';
import { createMockProgress } from '@/test/factories/workflowFactory';

describe('ExecutionSummaryBlock', () => {
  it('renders the execution summary rows', () => {
    render(<ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />);

    const block = screen.getByTestId('execution-summary');
    expect(block).toHaveTextContent('EXECUTION SUMMARY');
    expect(block).toHaveTextContent('exec-091-qp-55');
    expect(block).toHaveTextContent('SESSION_A92_XP_2024');
    expect(block).toHaveTextContent('RUNNING');
    expect(block).toHaveTextContent('14:22:01');
  });

  it('shows the placeholder end time for a running execution', () => {
    render(<ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />);
    expect(screen.getByTestId('execution-summary')).toHaveTextContent('--:--:--');
  });

  it('renders the never-run state with placeholders and the route session id', () => {
    render(<ExecutionSummaryBlock progress={null} sessionId="SESSION_A92_XP_2024" />);

    const block = screen.getByTestId('execution-summary');
    expect(block).toHaveTextContent('—');
    expect(block).toHaveTextContent('SESSION_A92_XP_2024');
    expect(block).toHaveTextContent('NOT RUN');
  });

  it('renders the end time once the execution finishes', () => {
    render(
      <ExecutionSummaryBlock
        progress={createMockProgress({ status: 'COMPLETED', endTime: '2026-07-26T14:25:33' })}
        sessionId="s1"
      />
    );
    expect(screen.getByTestId('execution-summary')).toHaveTextContent('14:25:33');
  });
});
