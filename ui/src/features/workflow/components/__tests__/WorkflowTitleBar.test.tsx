// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowTitleBar } from '../WorkflowTitleBar';

describe('WorkflowTitleBar', () => {
  it('titles the page with the workflow ID and its description, with no status pill', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf-982-xk-11"
        description="Supply chain optimizer"
        actions={<button type="button">Run</button>}
      />
    );

    expect(
      screen.getByRole('heading', { name: 'wf-982-xk-11' })
    ).toBeInTheDocument();
    expect(screen.getByText('Supply chain optimizer')).toBeInTheDocument();
    expect(screen.queryByTestId('status-chip')).toBeNull();
    expect(screen.getByRole('button', { name: 'Run' })).toBeInTheDocument();
  });

  it('has no description line when there is none', () => {
    const { container } = render(<WorkflowTitleBar workflowId="wf1" />);
    expect(container.querySelector('p')).toBeNull();
  });

  it('draws the hero backdrop behind the title row, outside the accessibility tree', () => {
    render(<WorkflowTitleBar workflowId="wf1" />);
    const backdrop = screen.getByTestId('hero-backdrop');
    expect(backdrop).toHaveAttribute('aria-hidden', 'true');
    expect(backdrop).toHaveClass('hero-backdrop');
  });

  it('puts the run group beside the title, left of the ml-auto container', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        runGroup={<span data-testid="run-group">group</span>}
        controls={<span data-testid="controls">controls</span>}
        actions={<button type="button">Act</button>}
      />
    );
    const heading = screen.getByRole('heading', { name: 'wf1' });
    const group = screen.getByTestId('run-group');
    expect(group.parentElement).toBe(heading.parentElement);
    expect(group.closest('.ml-auto')).toBeNull();
  });

  it('right-aligns the controls before the actions, wrapping', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
        controls={<span data-testid="controls">controls</span>}
        actions={<button type="button">Act</button>}
      />
    );
    const controls = screen.getByTestId('controls');
    const actions = screen.getByRole('button', { name: 'Act' });
    const right = controls.closest('.ml-auto');
    expect(right).toContainElement(actions);
    expect(right).toHaveClass('flex-wrap', 'justify-end');
    expect(
      controls.compareDocumentPosition(actions) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
  });

  it('renders only the actions in the ml-auto container without a run group', () => {
    render(
      <WorkflowTitleBar
        workflowId="wf1"
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
        description={'A very long description of the workflow. '.repeat(20)}
      />
    );
    const paragraph = container.querySelector('p');
    expect(paragraph).not.toBeNull();
    expect(paragraph?.className).not.toMatch(/max-w-/);
  });
});
