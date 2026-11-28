// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useNodes, useWorkflows } from '@/features/metrics/hooks';
import { HotspotsPanel, WorkflowsPanel } from '../WorkflowPanels';

vi.mock('@/features/metrics/hooks', () => ({
  useWorkflows: vi.fn(),
  useNodes: vi.fn(),
}));
vi.mock('../WorkflowDrawer', () => ({
  WorkflowDrawer: ({
    workflow,
    onClose,
  }: {
    workflow: { workflowId: string };
    onClose: () => void;
  }) => (
    <div>
      <p>drawer:{workflow.workflowId}</p>
      <button onClick={onClose}>close-drawer</button>
    </div>
  ),
}));

const workflows = vi.mocked(useWorkflows);
const nodes = vi.mocked(useNodes);
const ok = (data: unknown) =>
  ({ isPending: false, error: null, data, refetch: vi.fn() }) as never;

const row = (workflowId: string, over: Record<string, unknown> = {}) => ({
  workflowId,
  runs: 3,
  failures: 0,
  p50Ms: 1000,
  p95Ms: 2000,
  ...over,
});

const node = (nodeId: string, over: Record<string, unknown> = {}) => ({
  sessionId: 's1',
  workflowId: 'wf',
  nodeId,
  module: 'echo',
  runs: 10,
  failures: 0,
  p50Ms: 1,
  p95Ms: 2,
  lastExecutionId: 'e1',
  ...over,
});

describe('WorkflowsPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists each workflow with its runs, failures and run times, dashes for what was not timed', () => {
    workflows.mockReturnValue(
      ok([
        row('slow', { failures: 2, p95Ms: 4000 }),
        row('untimed', { p50Ms: 0, p95Ms: 0 }),
      ])
    );
    renderWithProviders(<WorkflowsPanel range="1h" live />);

    const slow = screen.getByRole('button', { name: 'slow' }).closest('tr');
    expect(slow).toHaveTextContent('2');
    expect(slow).toHaveTextContent('1.0 s');
    expect(slow).toHaveTextContent('4.0 s');
    const untimed = screen
      .getByRole('button', { name: 'untimed' })
      .closest('tr');
    expect(within(untimed as HTMLElement).getAllByText('–')).toHaveLength(3);
    expect(workflows).toHaveBeenCalledWith('1h', true);
  });

  it('shows the first rows and all of them on request', () => {
    workflows.mockReturnValue(
      ok(Array.from({ length: 10 }, (_, i) => row(`wf${i}`)))
    );
    renderWithProviders(<WorkflowsPanel range="1h" live={false} />);
    expect(screen.queryByRole('button', { name: 'wf9' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show all 10' }));
    expect(screen.getByRole('button', { name: 'wf9' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Show fewer' }));
    expect(screen.queryByRole('button', { name: 'wf9' })).toBeNull();
  });

  it('opens the drawer of the workflow clicked, and closes it', () => {
    workflows.mockReturnValue(ok([row('a'), row('b')]));
    renderWithProviders(<WorkflowsPanel range="6h" live />);
    fireEvent.click(screen.getByRole('button', { name: 'b' }));
    expect(screen.getByText('drawer:b')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'close-drawer' }));
    expect(screen.queryByText(/drawer:/)).toBeNull();
  });

  it('says so when no run finished, and retries a failed read', () => {
    workflows.mockReturnValue(ok([]));
    const { unmount } = renderWithProviders(<WorkflowsPanel range="1h" live />);
    expect(
      screen.getByText('No runs finished in this range')
    ).toBeInTheDocument();
    unmount();

    const refetch = vi.fn();
    workflows.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch,
    } as never);
    renderWithProviders(<WorkflowsPanel range="1h" live />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('HotspotsPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists the nodes that failed most, with how many of their runs failed', () => {
    nodes.mockReturnValue(
      ok([
        node('calm'),
        node('some', { failures: 1, runs: 4 }),
        node('worst', { failures: 6, runs: 8, lastExecutionId: 'e7' }),
      ])
    );
    renderWithProviders(<HotspotsPanel scope="global" range="6h" live />);

    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('worst');
    expect(items[0]).toHaveTextContent('6 of 8 (75%)');
    expect(
      within(items[0]).getByRole('link', { name: 'worst' })
    ).toHaveAttribute('href', '/sessions/s1/workflow/wf?exec=e7&node=worst');
    expect(items[1]).toHaveTextContent('1 of 4 (25%)');
    expect(screen.queryByText('calm')).toBeNull();
    expect(nodes).toHaveBeenCalledWith('6h', 'global', true);
  });

  it('lists five at most', () => {
    nodes.mockReturnValue(
      ok(
        Array.from({ length: 8 }, (_, i) => node(`n${i}`, { failures: i + 1 }))
      )
    );
    renderWithProviders(<HotspotsPanel scope="global" range="1h" live />);
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
  });

  it('shows nothing when no node failed, while loading and when it cannot load', () => {
    nodes.mockReturnValue(ok([node('calm')]));
    const { container, rerender } = renderWithProviders(
      <HotspotsPanel scope="global" range="1h" live />
    );
    expect(container).toBeEmptyDOMElement();

    nodes.mockReturnValue({
      isPending: true,
      error: null,
      data: undefined,
      refetch: vi.fn(),
    } as never);
    rerender(<HotspotsPanel scope="global" range="1h" live />);
    expect(container).toBeEmptyDOMElement();

    nodes.mockReturnValue({
      isPending: false,
      error: new Error('x'),
      data: undefined,
      refetch: vi.fn(),
    } as never);
    rerender(<HotspotsPanel scope="global" range="1h" live />);
    expect(container).toBeEmptyDOMElement();
  });
});
