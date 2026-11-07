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

  it('draws the hero backdrop behind the title row, outside the accessibility tree', () => {
    render(<WorkflowTitleBar workflowId="wf1" status={null} />);
    const backdrop = screen.getByTestId('hero-backdrop');
    expect(backdrop).toHaveAttribute('aria-hidden', 'true');
    expect(backdrop).toHaveClass('hero-backdrop');
  });

  it('puts the run group in the ml-auto container before the actions', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        status={null}
        runGroup={<span data-testid="run-group">group</span>}
        actions={<button type="button">Act</button>}
      />
    );
    const group = screen.getByTestId('run-group');
    const actions = screen.getByRole('button', { name: 'Act' });
    const right = group.closest('.ml-auto');
    expect(right).not.toBeNull();
    expect(right).toContainElement(actions);
    expect(
      group.compareDocumentPosition(actions) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
  });

  it('does not place the run group beside the title outside the ml-auto container', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        status={null}
        runGroup={<span data-testid="run-group">group</span>}
      />
    );
    const heading = screen.getByRole('heading', { name: 'wf1' });
    const group = screen.getByTestId('run-group');
    expect(group.closest('.ml-auto')).not.toBeNull();
    expect(group.parentElement).not.toBe(heading.parentElement);
  });

  it('keeps the right container wrapping and right-aligned', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        status={null}
        runGroup={<span data-testid="run-group">group</span>}
      />
    );
    const right = screen.getByTestId('run-group').closest('.ml-auto');
    expect(right).toHaveClass('flex-wrap', 'justify-end');
  });

  it('renders only the actions in the ml-auto container without a run group', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        status={null}
        actions={<button type="button">Act</button>}
      />
    );
    const right = screen
      .getByRole('button', { name: 'Act' })
      .closest('.ml-auto');
    expect(right).not.toBeNull();
    expect(right?.children).toHaveLength(1);
  });

  it('sets no max-width class on a long description', () => {
    const { container } = render(
      <WorkflowTitleBar
        workflowId="wf1"
        status={null}
        description={'A very long description of the workflow. '.repeat(20)}
      />
    );
    const paragraph = container.querySelector('p');
    expect(paragraph).not.toBeNull();
    expect(paragraph?.className).not.toMatch(/max-w-/);
  });
});
