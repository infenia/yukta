// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createRef, type RefObject } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, RouterProvider, createMemoryRouter } from 'react-router';
import { LeaveGuard } from '../LeaveGuard';

function renderAt(dirty: boolean, leaving?: RefObject<boolean>) {
  const ref = leaving ?? ({ current: false } as RefObject<boolean>);
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: (
          <>
            <LeaveGuard dirty={dirty} leaving={ref} />
            <Link to="/elsewhere">go</Link>
          </>
        ),
      },
      { path: '/elsewhere', element: <p>elsewhere</p> },
    ],
    { initialEntries: ['/'] }
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('LeaveGuard', () => {
  it('asks before leaving unsaved edits, and stays on Stay', async () => {
    const router = renderAt(true);
    await userEvent.click(screen.getByRole('link', { name: 'go' }));
    expect(
      await screen.findByRole('dialog', { name: 'Leave without saving?' })
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Stay' }));
    expect(router.state.location.pathname).toBe('/');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('leaves on Leave', async () => {
    const router = renderAt(true);
    await userEvent.click(screen.getByRole('link', { name: 'go' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Leave' }));
    expect(router.state.location.pathname).toBe('/elsewhere');
  });

  it('does not ask when nothing is unsaved, or when the page itself is leaving', async () => {
    renderAt(false);
    await userEvent.click(screen.getByRole('link', { name: 'go' }));
    expect(await screen.findByText('elsewhere')).toBeInTheDocument();
  });

  it('does not ask when the page is leaving after a save', async () => {
    const leaving = createRef<boolean>() as { current: boolean };
    leaving.current = true;
    renderAt(true, leaving);
    await userEvent.click(screen.getByRole('link', { name: 'go' }));
    expect(await screen.findByText('elsewhere')).toBeInTheDocument();
  });

  it('closes the dialog with Escape, staying put', async () => {
    const router = renderAt(true);
    await userEvent.click(screen.getByRole('link', { name: 'go' }));
    await screen.findByRole('dialog');
    await userEvent.keyboard('{Escape}');
    expect(router.state.location.pathname).toBe('/');
  });
});
