// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowTitleBar } from '../WorkflowTitleBar';

describe('WorkflowTitleBar', () => {
  it('titles the page with the workflow ID, its status and its description', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf-982-xk-11"
        description="Supply chain optimizer"
        status="RUNNING"
        actions={<button type="button">Run</button>}
      />
    );

    expect(
      screen.getByRole('heading', { name: 'wf-982-xk-11' })
    ).toBeInTheDocument();
    expect(screen.getByText('Supply chain optimizer')).toBeInTheDocument();
    expect(screen.getByTestId('status-chip')).toHaveTextContent('Running');
    expect(screen.getByRole('button', { name: 'Run' })).toBeInTheDocument();
  });

  it('shows Not run, and no description line when there is none', () => {
    const { container } = render(
      <WorkflowTitleBar workflowId="wf1" status={null} />
    );
    expect(container.querySelector('p')).toBeNull();
    expect(screen.getByTestId('status-chip')).toHaveTextContent('Not run');
  });
});
