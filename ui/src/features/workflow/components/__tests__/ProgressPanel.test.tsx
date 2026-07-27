// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProgressPanel } from '../ProgressPanel';
import { createMockGraph, createMockProgress } from '@/test/factories/workflowFactory';

describe('ProgressPanel', () => {
  it('renders the heading, summary and task sequence', () => {
    render(
      <ProgressPanel
        graph={createMockGraph()}
        progress={createMockProgress()}
        tasksByNode={{}}
        sessionId="s1"
        onClose={vi.fn()}
      />
    );

    expect(screen.getByTestId('progress-panel')).toHaveTextContent('WORKFLOW PROGRESS');
    expect(screen.getByTestId('execution-summary')).toBeInTheDocument();
    expect(screen.getByTestId('task-sequence')).toBeInTheDocument();
  });

  it('notifies close', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <ProgressPanel
        graph={createMockGraph()}
        progress={null}
        tasksByNode={{}}
        sessionId="s1"
        onClose={onClose}
      />
    );

    await user.click(screen.getByRole('button', { name: /close workflow progress/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
