// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { preText } from '@/test/preText';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import type { WorkflowGraphNode } from '../../types/workflow';
import { NodeSheet } from '../NodeSheet';

vi.mock('@/features/supervisors/components/NodeSupervisorTab', () => ({
  NodeSupervisorTab: ({ nodeId }: { nodeId: string }) => (
    <p>supervisor of {nodeId}</p>
  ),
}));
const getDefinition = vi.fn();
vi.mock('../../api/getWorkflowDefinition', () => ({
  getWorkflowDefinition: (...args: unknown[]) => getDefinition(...args),
}));
const getStart = vi.fn();
vi.mock('../../api/getStartPayload', () => ({
  getStartPayload: (...args: unknown[]) => getStart(...args),
}));
let resultEvents: { nodeId: string; sequence: number; payload: unknown }[] = [];
vi.mock('../../hooks/useExecutionResults', () => ({
  useExecutionResults: () => ({ results: resultEvents }),
}));

const node = (nodeId: string, category: WorkflowGraphNode['category']) =>
  ({ nodeId, type: 'T', category }) as WorkflowGraphNode;

function open(
  over: Partial<Parameters<typeof NodeSheet>[0]> = {},
  onCommand = vi.fn()
) {
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <NodeSheet
        node={node('work', 'PROCESSOR')}
        task={{
          nodeId: 'work',
          module: 'T',
          status: 'RUNNING',
          startTime: '2026-01-01T10:00:00',
          endTime: null,
        }}
        sessionId="s"
        workflowId="w"
        executionId="e1"
        tab="node"
        onTab={vi.fn()}
        onCommand={onCommand}
        onClose={vi.fn()}
        {...over}
      />
    </QueryClientProvider>
  );
  return onCommand;
}

beforeEach(() => {
  resultEvents = [
    { nodeId: 'sink', sequence: 1, payload: { ok: true } },
    { nodeId: 'other', sequence: 2, payload: { no: 1 } },
  ];
  getDefinition.mockReset();
  getStart.mockReset();
  getDefinition.mockResolvedValue({
    workflowId: 'w',
    nodes: [{ nodeId: 'work', type: 'T', config: { a: 1 } }],
  });
});

describe('NodeSheet', () => {
  it('shows the Node tab by default and moves to the Supervisor tab on a click', async () => {
    const onTab = vi.fn();
    open({ onTab });
    expect(screen.getByRole('tab', { name: 'Node' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.queryByText('supervisor of work')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Supervisor' }));
    expect(onTab).toHaveBeenCalledWith('supervisor');
  });

  it("shows who supervises the node in place of the node's details on the Supervisor tab", () => {
    open({ tab: 'supervisor' });
    expect(screen.getByRole('tab', { name: 'Supervisor' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByText('supervisor of work')).toBeInTheDocument();
    expect(screen.queryByText('hello')).not.toBeInTheDocument();
  });

  it('shows what the node started with, as formatted JSON, and falls back to the raw text', () => {
    const task = {
      nodeId: 'work',
      module: 'T',
      status: 'RUNNING',
      startTime: '2026-01-01T10:00:00',
      endTime: null,
      metadata: { input: '{"branch":"main"}' },
    };
    const { unmount } = render(
      <QueryClientProvider client={createTestQueryClient()}>
        <NodeSheet
          node={node('work', 'PROCESSOR')}
          task={task}
          sessionId="s"
          workflowId="w"
          executionId="e1"
          tab="node"
          onTab={vi.fn()}
          onCommand={vi.fn()}
          onClose={vi.fn()}
        />
      </QueryClientProvider>
    );
    expect(screen.getByText('Started with')).toBeInTheDocument();
    expect(document.body).toHaveTextContent('"branch": "main"');
    unmount();

    open({
      task: { ...task, metadata: { input: '{"cut": "tru... [truncated' } },
    });
    expect(document.body).toHaveTextContent('[truncated');
  });

  it('has no Started with section when the node reported no input', () => {
    open();
    expect(screen.queryByText('Started with')).not.toBeInTheDocument();
  });

  it('shows its config and sends its logs to the dock', async () => {
    const onShowLogs = vi.fn();
    open({ onShowLogs });
    expect(await screen.findByText(preText(/"a": 1/))).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: "Show this node's logs" })
    );
    expect(onShowLogs).toHaveBeenCalledWith('work');
  });

  it('has no logs button without a dock to show them in', () => {
    open();
    expect(
      screen.queryByRole('button', { name: "Show this node's logs" })
    ).not.toBeInTheDocument();
  });

  it('keeps every action in its cell and disables those that do not apply', () => {
    open();
    expect(screen.getByRole('button', { name: 'Pause node' })).toBeEnabled();
    const resume = screen.getByRole('button', { name: 'Resume node' });
    expect(resume).toBeDisabled();
    expect(resume).toHaveAttribute(
      'title',
      'Resume node: not available while running'
    );
    expect(screen.getByRole('button', { name: 'Stop node…' })).toHaveAttribute(
      'title',
      'Stop node…'
    );
    expect(
      screen.getByRole('button', { name: 'Set breakpoint…' })
    ).toBeEnabled();
  });

  it('puts Clear breakpoint in the breakpoint cell when one is set', () => {
    open({
      task: {
        nodeId: 'work',
        module: 'T',
        status: 'RUNNING',
        startTime: null,
        endTime: null,
        breakpoint: { field: 'x', equals: 'y' },
      },
    });
    expect(
      screen.getByRole('button', { name: 'Clear breakpoint' })
    ).toBeEnabled();
    expect(
      screen.queryByRole('button', { name: 'Set breakpoint…' })
    ).not.toBeInTheDocument();
  });

  it('offers the actions of its status and reports the one chosen', async () => {
    const onCommand = open();
    await userEvent.click(screen.getByRole('button', { name: 'Pause node' }));
    expect(onCommand).toHaveBeenCalledWith('work', 'pause');
  });

  it('offers no actions without a run', () => {
    open({ executionId: null, task: undefined });
    expect(screen.queryByText('Actions')).not.toBeInTheDocument();
  });

  it('says so when the node has no config', async () => {
    getDefinition.mockResolvedValue({ workflowId: 'w', nodes: [] });
    open();
    expect(await screen.findByText('No configuration.')).toBeInTheDocument();
  });

  it('says so when the config cannot be loaded', async () => {
    getDefinition.mockRejectedValue(new Error('x'));
    open();
    expect(
      await screen.findByText('The configuration could not be loaded.')
    ).toBeInTheDocument();
  });

  it('shows what a terminal received, and only a terminal has the section', () => {
    open({ node: node('sink', 'TERMINAL') });
    expect(screen.getByText(preText(/"ok": true/))).toBeInTheDocument();
    expect(screen.queryByText(/"no": 1/)).not.toBeInTheDocument();
  });

  it('says so when a terminal has received nothing yet', () => {
    resultEvents = [];
    open({ node: node('sink', 'TERMINAL') });
    expect(screen.getByText('No result yet.')).toBeInTheDocument();
  });

  it('has no Result section for a processor', () => {
    open();
    expect(screen.queryByText('Result')).not.toBeInTheDocument();
  });

  describe('start values', () => {
    const withStartRefs = () =>
      getDefinition.mockResolvedValue({
        workflowId: 'w',
        nodes: [
          {
            nodeId: 'work',
            type: 'T',
            config: {
              env: { L: '${start.label}', N: '${start.opts.n:int}' },
              args: ['${start.label}', '${start.missing}'],
            },
          },
        ],
      });

    it('shows what each ${start.…} of the config got on this run', async () => {
      withStartRefs();
      getStart.mockResolvedValue({
        payload: { label: 'lint', opts: { n: 3 } },
      });
      open();
      const values = await screen.findByLabelText('Start values');
      expect(values).toHaveTextContent('start.labellint');
      expect(values).toHaveTextContent('start.opts.n3');
      expect(values).toHaveTextContent('start.missingnot set');
      expect(getStart).toHaveBeenCalledWith('e1');
    });

    it('says so when the server no longer holds the start payload', async () => {
      withStartRefs();
      getStart.mockResolvedValue({ payload: null });
      open();
      expect(
        await screen.findByText(
          'The server no longer holds what this run was started with.'
        )
      ).toBeInTheDocument();
    });

    it('says so when the start payload cannot be loaded', async () => {
      withStartRefs();
      getStart.mockRejectedValue(new Error('boom'));
      open();
      expect(
        await screen.findByText(
          'What this run was started with could not be loaded.'
        )
      ).toBeInTheDocument();
    });

    it('asks for nothing without a run', async () => {
      withStartRefs();
      open({ executionId: null });
      expect(
        await screen.findByText(preText(/start\.label/))
      ).toBeInTheDocument();
      expect(getStart).not.toHaveBeenCalled();
    });

    it('asks for nothing when the config has no ${start.…}', async () => {
      open();
      expect(await screen.findByText(preText(/"a": 1/))).toBeInTheDocument();
      expect(getStart).not.toHaveBeenCalled();
    });
  });
});
