// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { DraftWorkflow } from '../../types';
import { TestPanel } from '../TestPanel';
import { startTestRun } from '../../api';
import { readPin, writePin } from '../../lib/testPins';

vi.mock('../../api', () => ({ startTestRun: vi.fn() }));
let state = {
  results: [] as { payload: unknown }[],
  complete: false,
  executionStatus: null as string | null,
};
vi.mock('@/features/workflow/hooks/useExecutionResults', () => ({
  useExecutionResults: () => state,
}));

const workflow: DraftWorkflow = {
  workflowId: 'w',
  description: '',
  nodes: [{ nodeId: 'a', type: 'T', config: {} }],
  edges: [],
};
const show = () =>
  renderWithProviders(
    <TestPanel sessionId="s" workflow={workflow} nodeId="a" />
  );

beforeEach(() => {
  sessionStorage.clear();
  state = { results: [], complete: false, executionStatus: null };
  vi.mocked(startTestRun).mockReset();
  vi.mocked(startTestRun).mockResolvedValue({
    executionId: 'e',
    workflowId: 'x',
  });
});

describe('TestPanel', () => {
  it('runs up to the node with the sample', async () => {
    show();
    fireEvent.change(screen.getByLabelText(/Sample input/), {
      target: { value: '{"a":1}' },
    });
    await userEvent.click(
      screen.getByRole('button', { name: 'Run up to here' })
    );
    expect(startTestRun).toHaveBeenCalledWith('s', {
      workflow,
      untilNodeId: 'a',
      stepOnly: false,
      payload: { a: 1 },
    });
  });

  it('tests the step alone', async () => {
    show();
    await userEvent.click(
      screen.getByRole('button', { name: 'Test this step' })
    );
    expect(vi.mocked(startTestRun).mock.calls[0][1].stepOnly).toBe(true);
    expect(await screen.findByText('Running…')).toBeInTheDocument();
  });

  it('blocks a sample that is not an object', () => {
    show();
    fireEvent.change(screen.getByLabelText(/Sample input/), {
      target: { value: '[1]' },
    });
    expect(
      screen.getByText('The sample must be a JSON object.')
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Test this step' })
    ).toBeDisabled();
  });

  it('shows the output and pins it when the run ends', async () => {
    state = {
      results: [{ payload: { ok: true } }],
      complete: true,
      executionStatus: 'COMPLETED',
    };
    show();
    await userEvent.click(
      screen.getByRole('button', { name: 'Run up to here' })
    );
    expect(await screen.findByText('Finished: COMPLETED')).toBeInTheDocument();
    expect(screen.getByText(/"ok"/)).toBeInTheDocument();
    await waitFor(() =>
      expect(readPin('s', 'w', 'a')?.output).toEqual([{ ok: true }])
    );
  });

  it('lists several results as an array', async () => {
    state = {
      results: [{ payload: 1 }, { payload: 2 }],
      complete: true,
      executionStatus: null,
    };
    show();
    await userEvent.click(
      screen.getByRole('button', { name: 'Run up to here' })
    );
    await waitFor(() =>
      expect(document.querySelector('pre')?.textContent).toBe('[\n  1,\n  2\n]')
    );
  });

  it('shows the pinned sample and output of an earlier run', () => {
    writePin('s', 'w', 'a', { sample: '{"p":1}', output: [{ x: 1 }] });
    show();
    expect(screen.getByLabelText(/Sample input/)).toHaveValue('{"p":1}');
    expect(screen.getByText(/"x"/)).toBeInTheDocument();
  });

  it('shows why the server refused', async () => {
    vi.mocked(startTestRun).mockRejectedValue(new Error('no limit left'));
    show();
    await userEvent.click(
      screen.getByRole('button', { name: 'Test this step' })
    );
    expect(await screen.findByText('no limit left')).toBeInTheDocument();
  });
});
