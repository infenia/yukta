// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import { NodeControlsMenu } from '../NodeControlsMenu';
import { RunControls } from '../RunControls';

let written: string[] = [];
let clipboardFails = false;

beforeEach(() => {
  written = [];
  clipboardFails = false;
});

/** user-event installs its own clipboard on setup, so ours goes in afterwards. */
function setupUser() {
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: (text: string) => {
        if (clipboardFails) return Promise.reject(new Error('denied'));
        written.push(text);
        return Promise.resolve();
      },
    },
  });
  return user;
}

afterEach(() => vi.restoreAllMocks());

/** Opens a "Copy as" submenu with the keyboard (pointer hover does not open it in jsdom). */
async function openSubmenu(
  user: ReturnType<typeof userEvent.setup>,
  format: RegExp
) {
  const trigger = await screen.findByRole('menuitem', { name: format });
  trigger.focus();
  await user.keyboard('{ArrowRight}');
  await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'true'));
  await waitFor(() => expect(document.activeElement).not.toBe(trigger));
}

/** Copies through a submenu: moves down to the item with this label and presses Enter. */
async function pickFromSubmenu(
  user: ReturnType<typeof userEvent.setup>,
  format: string,
  item: string
) {
  await openSubmenu(user, new RegExp(`^${format}`));
  for (
    let i = 0;
    i < 12 && document.activeElement?.textContent !== item;
    i += 1
  ) {
    await user.keyboard('{ArrowDown}');
  }
  expect(document.activeElement?.textContent).toBe(item);
  await user.keyboard('{Enter}');
}

/** The labels a submenu offers. */
async function submenuItems(
  user: ReturnType<typeof userEvent.setup>,
  format: RegExp
) {
  await openSubmenu(user, format);
  const triggers = 3;
  return screen
    .getAllByRole('menuitem')
    .slice(triggers)
    .map((i) => i.textContent);
}

describe('NodeControlsMenu copy as', () => {
  const scope = { sessionId: 's1', executionId: 'e1' };
  const open = async (status: 'running' | 'failed' | 'completed') => {
    const user = setupUser();
    render(
      <NodeControlsMenu
        nodeId="n1"
        status={status}
        onCommand={vi.fn()}
        scope={scope}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Actions for n1' }));
    return user;
  };

  it('has no copy entries without a run', async () => {
    const user = setupUser();
    render(
      <NodeControlsMenu nodeId="n1" status="running" onCommand={vi.fn()} />
    );
    await user.click(screen.getByRole('button', { name: 'Actions for n1' }));
    await screen.findByRole('menuitem', { name: 'Pause node' });
    expect(
      screen.queryByRole('menuitem', { name: /^Copy as CLI/ })
    ).not.toBeInTheDocument();
  });

  it('copies a node action as CLI and confirms', async () => {
    const user = await open('running');
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause node');
    await waitFor(() =>
      expect(written).toEqual([
        `yukta-cli workflow node pause s1 e1 n1 --url ${window.location.origin}`,
      ])
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
  });

  it('copies as curl and as MCP, including restart from here', async () => {
    const user = await open('failed');
    await pickFromSubmenu(user, 'Copy as curl', 'Retry node');
    await waitFor(() => expect(written).toHaveLength(1));
    expect(written[0]).toContain('/api/executions/e1/nodes/n1/retry');

    await user.click(screen.getByRole('button', { name: 'Actions for n1' }));
    await pickFromSubmenu(user, 'Copy as MCP', 'Restart from here');
    await waitFor(() => expect(written).toHaveLength(2));
    expect(JSON.parse(written[1])).toMatchObject({
      name: 'control_workflow',
      arguments: { action: 'RESTART_FROM_NODE', fromNodeId: 'n1' },
    });
  });

  it('says so when the clipboard refuses', async () => {
    clipboardFails = true;
    const user = await open('running');
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause node');
    expect(await screen.findByRole('status')).toHaveTextContent('Copy failed');
  });

  it('says so when there is no clipboard', async () => {
    const user = await open('running');
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause node');
    expect(await screen.findByRole('status')).toHaveTextContent('Copy failed');
  });
});

describe('RunControls copy as', () => {
  const setup = async (
    status: string | null,
    executionId: string | null = 'e1'
  ) => {
    const user = setupUser();
    renderWithProviders(
      <RunControls
        sessionId="s1"
        workflowId="wf"
        executionId={executionId}
        status={status}
        onStarted={vi.fn()}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Copy as' }));
    return user;
  };

  it('offers to copy starting and stopping all runs even before any run', async () => {
    const user = await setup(null, null);
    const items = await submenuItems(user, /^Copy as CLI/);
    expect(items).toContain('Run workflow');
    expect(items).toContain('Stop all runs (KILL)');
    expect(items).not.toContain('Pause run');
    await pickFromSubmenu(user, 'Copy as CLI', 'Stop all runs (KILL)');
    await waitFor(() =>
      expect(written).toEqual([
        `yukta-cli workflow stop s1 wf --mode KILL --url ${window.location.origin}`,
      ])
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
  });

  it.each([
    ['RUNNING', ['Pause run', 'Stop this run']],
    ['PAUSED', ['Resume run', 'Stop this run']],
    ['COMPLETED', ['Restart run']],
  ])('offers the actions that fit a %s run', async (status, labels) => {
    const user = await setup(status);
    const items = await submenuItems(user, /^Copy as MCP/);
    expect(items).toEqual(expect.arrayContaining(['Run workflow', ...labels]));
  });

  it('offers no run action without a selected run, whatever the status says', async () => {
    const user = await setup('RUNNING', null);
    const items = await submenuItems(user, /^Copy as CLI/);
    expect(items).not.toContain('Pause run');
    expect(items).toContain('Run workflow');
  });

  it('says so when the clipboard refuses a run command', async () => {
    clipboardFails = true;
    const user = await setup('RUNNING');
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause run');
    expect(await screen.findByRole('status')).toHaveTextContent('Copy failed');
  });

  it('hides the confirmation again after a moment', async () => {
    const user = await setup('RUNNING');
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause run');
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
    await waitFor(
      () => expect(screen.queryByRole('status')).not.toBeInTheDocument(),
      {
        timeout: 3000,
      }
    );
  });

  it('stops the confirmation timer when the controls go away', async () => {
    const user = await setup('RUNNING');
    await pickFromSubmenu(user, 'Copy as CLI', 'Pause run');
    expect(await screen.findByRole('status')).toHaveTextContent('Copied');
    cleanup();
  });

  it('copies a run action', async () => {
    const user = await setup('RUNNING');
    await pickFromSubmenu(user, 'Copy as curl', 'Pause run');
    await waitFor(() => expect(written).toHaveLength(1));
    expect(written[0]).toContain('/api/executions/e1/pause');
    expect(written[0]).toContain('$YUKTA_TOKEN');
  });
});
