// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import { createMockSession } from '@/test/factories/sessionFactory';
import { SessionRowActionsMenu } from '../SessionRowActionsMenu';
import { exportSessionConfig } from '../../lib/exportSession';

vi.mock('../../lib/exportSession', async () => ({
  ...(await vi.importActual<object>('../../lib/exportSession')),
  exportSessionConfig: vi.fn(),
}));

afterEach(() => vi.clearAllMocks());

async function openMenu() {
  const user = userEvent.setup();
  render(
    <BrowserRouter>
      <SessionRowActionsMenu
        session={createMockSession({ sessionId: 'sess-9' })}
      />
    </BrowserRouter>
  );
  await user.click(screen.getByRole('button', { name: 'Session actions' }));
  return user;
}

describe('SessionRowActionsMenu export', () => {
  it('exports the session config', async () => {
    vi.mocked(exportSessionConfig).mockResolvedValue();
    const user = await openMenu();
    const item = await screen.findByRole('menuitem', { name: 'Export JSON' });
    expect(item).toHaveAttribute('title', 'Secret values are not exported.');
    await user.click(item);
    expect(exportSessionConfig).toHaveBeenCalledWith('sess-9');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says so when the export fails', async () => {
    vi.mocked(exportSessionConfig).mockRejectedValue(new Error('gone'));
    const user = await openMenu();
    await user.click(
      await screen.findByRole('menuitem', { name: 'Export JSON' })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Export failed');
  });
});
