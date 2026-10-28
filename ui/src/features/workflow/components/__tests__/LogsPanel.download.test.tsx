// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';
import { createMockLogEntry } from '@/test/factories/workflowFactory';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';

const entries = [
  createMockLogEntry({ message: 'alpha line', level: 'INFO' }),
  createMockLogEntry({ message: 'beta line', level: 'ERROR' }),
];

let blobs: Blob[] = [];
let names: string[] = [];

beforeEach(() => {
  installMockEventSource();
  blobs = [];
  names = [];
  URL.createObjectURL = vi.fn((blob: Blob) => {
    blobs.push(blob);
    return 'blob:x';
  });
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement
  ) {
    names.push(this.download);
  });
});
afterEach(() => vi.restoreAllMocks());

const panel = (props: Partial<React.ComponentProps<typeof LogsPanel>> = {}) => (
  <LogsPanel
    entries={entries}
    isStreaming={false}
    sessionId="s1"
    executionId="e1"
    {...props}
  />
);

async function download(
  user: ReturnType<typeof userEvent.setup>,
  item: string
) {
  await user.click(screen.getByRole('button', { name: 'Download logs' }));
  await user.click(await screen.findByRole('menuitem', { name: item }));
}

describe('LogsPanel downloads', () => {
  it('downloads the visible lines, after the text filter, as <session>-<execution>.log', async () => {
    const user = userEvent.setup();
    render(panel());
    await user.type(screen.getByLabelText('Filter logs'), 'beta');
    await download(user, 'Visible lines (.log)');

    expect(names).toEqual(['s1-e1.log']);
    const text = await blobs[0].text();
    expect(text).toMatch(/^\[.*\] ERROR \[vectorize-batch\] beta line\n$/);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x');
  });

  it('downloads every received entry as JSON lines, whatever the filter', async () => {
    const user = userEvent.setup();
    render(panel());
    await user.type(screen.getByLabelText('Filter logs'), 'beta');
    await download(user, 'All entries (.jsonl)');

    expect(names).toEqual(['s1-e1.jsonl']);
    expect(await blobs[0].text()).toBe(
      entries.map((e) => JSON.stringify(e)).join('\n') + '\n'
    );
  });

  it('falls back to a plain name without a session and says how many lines are kept', async () => {
    const user = userEvent.setup();
    render(panel({ sessionId: '', executionId: null, dropped: 5 }));
    await user.click(screen.getByRole('button', { name: 'Download logs' }));
    expect(
      await screen.findByText(/includes only the 2 lines kept/)
    ).toBeInTheDocument();
    await user.click(
      screen.getByRole('menuitem', { name: 'Visible lines (.log)' })
    );
    expect(names).toEqual(['logs.log']);
  });
});

describe('LogsPanel raw mode', () => {
  it('cannot switch to raw without an execution', () => {
    render(panel({ executionId: null }));
    expect(screen.getByRole('button', { name: 'RAW' })).toBeDisabled();
  });

  it('streams the plain-text log of the execution and downloads those lines', async () => {
    const user = userEvent.setup();
    render(panel());
    await user.click(screen.getByRole('button', { name: 'RAW' }));
    const source = MockEventSource.byUrl('/api/sessions/s1/executions/e1/logs');
    expect(source.url).not.toContain('/entries');
    expect(screen.getByRole('button', { name: 'RAW' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );

    act(() => {
      source.emit('first raw line');
      source.emit('second raw line');
    });
    expect(screen.getByTestId('logs-body')).toHaveTextContent('first raw line');
    expect(screen.queryByText('alpha line')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Filter logs'), 'second');
    expect(screen.queryByText('first raw line')).not.toBeInTheDocument();
    await download(user, 'Visible lines (.log)');
    expect(await blobs[0].text()).toBe('second raw line\n');
  });

  it('starts from an empty list each time raw is switched on, and returns to structured', async () => {
    const user = userEvent.setup();
    render(panel());
    await user.click(screen.getByRole('button', { name: 'RAW' }));
    act(() => MockEventSource.latest().emit('old line'));
    expect(screen.getByTestId('logs-body')).toHaveTextContent('old line');

    await user.click(screen.getByRole('button', { name: 'STRUCTURED' }));
    expect(screen.getByTestId('logs-body')).toHaveTextContent('alpha line');

    await user.click(screen.getByRole('button', { name: 'RAW' }));
    expect(screen.getByTestId('logs-body')).not.toHaveTextContent('old line');
    act(() => MockEventSource.latest().emit('new line'));
    expect(screen.getByTestId('logs-body')).toHaveTextContent('new line');

    // already raw: a second click changes nothing
    await user.click(screen.getByRole('button', { name: 'RAW' }));
    expect(screen.getByTestId('logs-body')).toHaveTextContent('new line');
  });
});
