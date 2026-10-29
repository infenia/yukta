// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NodeControlsMenu } from '../NodeControlsMenu';
import type { NodeUiStatus } from '../../types/workflow';

async function itemsFor(status: NodeUiStatus) {
  const user = userEvent.setup();
  const onCommand = vi.fn();
  const { unmount } = render(
    <NodeControlsMenu nodeId="n1" status={status} onCommand={onCommand} />
  );
  await user.click(screen.getByRole('button', { name: 'Actions for n1' }));
  const labels = (await screen.findAllByRole('menuitem')).map(
    (item) => item.textContent
  );
  return { user, onCommand, labels, unmount };
}

describe('NodeControlsMenu', () => {
  it('offers pause, stop, skip and step-mode on a running node', async () => {
    const { labels } = await itemsFor('running');
    expect(labels).toEqual([
      'Pause node',
      'Stop node…',
      'Skip node',
      'Enable step mode',
      'Restart from here',
    ]);
  });

  it('offers resume, step and restart on a paused node', async () => {
    const { labels } = await itemsFor('paused');
    expect(labels).toEqual([
      'Resume node',
      'Stop node…',
      'Step once',
      'Disable step mode',
      'Restart from here',
    ]);
  });

  it('offers retry on a failed node and skip on a pending one', async () => {
    const failed = await itemsFor('failed');
    expect(failed.labels).toEqual(['Retry node', 'Restart from here']);
    failed.unmount();
    expect((await itemsFor('pending')).labels).toEqual(['Skip node']);
  });

  it('offers unskip on a skipped node', async () => {
    expect((await itemsFor('skipped')).labels).toEqual([
      'Unskip node',
      'Restart from here',
    ]);
  });

  it('offers restart-from on a finished node', async () => {
    expect((await itemsFor('completed')).labels).toEqual(['Restart from here']);
  });

  it('reports the chosen command with the node id', async () => {
    const { user, onCommand } = await itemsFor('running');
    await user.click(screen.getByRole('menuitem', { name: 'Pause node' }));
    expect(onCommand).toHaveBeenCalledWith('n1', 'pause');
  });
});
