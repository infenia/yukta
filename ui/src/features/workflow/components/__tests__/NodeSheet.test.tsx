// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import type { LogEntry, WorkflowGraphNode } from '../../types/workflow';
import { NodeSheet } from '../NodeSheet';

const getDefinition = vi.fn();
vi.mock('../../api/getWorkflowDefinition', () => ({
  getWorkflowDefinition: (...args: unknown[]) => getDefinition(...args),
}));
let resultEvents: { nodeId: string; sequence: number; payload: unknown }[] = [];
vi.mock('../../hooks/useExecutionResults', () => ({
  useExecutionResults: () => ({ results: resultEvents }),
}));

const node = (nodeId: string, category: WorkflowGraphNode['category']) =>
  ({ nodeId, type: 'T', category }) as WorkflowGraphNode;
const line = (pluginId: string, message: string) =>
  ({ pluginId, message }) as LogEntry;

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
        entries={[line('work', 'hello'), line('else', 'not mine')]}
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
  getDefinition.mockResolvedValue({
    workflowId: 'w',
    nodes: [{ nodeId: 'work', type: 'T', config: { a: 1 } }],
  });
});

describe('NodeSheet', () => {
  it("shows only this node's log lines and its config", async () => {
    open();
    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.queryByText('not mine')).not.toBeInTheDocument();
    expect(await screen.findByText(/"a": 1/)).toBeInTheDocument();
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
    expect(screen.getByText(/"ok": true/)).toBeInTheDocument();
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
});
