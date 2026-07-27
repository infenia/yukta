// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowTitleBar } from '../WorkflowTitleBar';

describe('WorkflowTitleBar', () => {
  it('renders the display name as heading, the ID and the status chip', () => {
    render(
      <WorkflowTitleBar
        displayName="Supply_Chain_Optimizer_V2"
        workflowId="wf-982-xk-11"
        status="RUNNING"
      />
    );

    expect(
      screen.getByRole('heading', { name: 'Supply_Chain_Optimizer_V2' })
    ).toBeInTheDocument();
    expect(screen.getByText('ID: wf-982-xk-11')).toBeInTheDocument();
    expect(screen.getByTestId('status-chip')).toHaveTextContent('RUNNING');
  });

  it('shows NOT RUN when there is no execution', () => {
    render(<WorkflowTitleBar displayName="wf1" workflowId="wf1" status={null} />);
    expect(screen.getByTestId('status-chip')).toHaveTextContent('NOT RUN');
  });
});
