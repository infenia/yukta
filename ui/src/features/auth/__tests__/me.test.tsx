// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { RequirePermission } from '@/components/common/RequirePermission';
import IdentityChip from '@/components/layout/IdentityChip';
import { API_KEY_REQUIRED_EVENT, getApiKey, setApiKey } from '@/lib/apiKey';
import { knownRole, setMe, type Me, type Permissions, type Role } from '@/lib/identity';
import { loadMe } from '../me';

const none: Permissions = {
  startRuns: false,
  controlRuns: false,
  editSessions: false,
  deleteSessions: false,
  changeLimits: false,
  sendCommands: false,
};
const ROLES: Record<Role, Permissions> = {
  VIEWER: none,
  OPERATOR: { ...none, startRuns: true, controlRuns: true },
  ADMIN: { startRuns: true, controlRuns: true, editSessions: true, deleteSessions: true, changeLimits: true, sendCommands: true },
};
const me = (role: Role, over: Partial<Me> = {}): Me => ({
  authenticated: true,
  keyName: 'alice',
  role,
  sessions: null,
  authEnabled: true,
  permissions: ROLES[role],
  ...over,
});

let reply: Response | null = null;
const server = setupServer(http.get('/api/me', () => reply ?? HttpResponse.error()));
beforeAll(() => server.listen());
afterEach(() => {
  setMe(null);
  reply = null;
  setApiKey(null);
});
afterAll(() => server.close());

describe('RequirePermission', () => {
  const cases: [Role, string, boolean][] = [
    ['VIEWER', 'startRuns', true],
    ['VIEWER', 'controlRuns', true],
    ['VIEWER', 'editSessions', true],
    ['OPERATOR', 'startRuns', false],
    ['OPERATOR', 'controlRuns', false],
    ['OPERATOR', 'editSessions', true],
    ['OPERATOR', 'changeLimits', true],
    ['ADMIN', 'startRuns', false],
    ['ADMIN', 'sendCommands', false],
  ];
  it.each(cases)('%s and %s: disabled = %s', (role, perm, disabled) => {
    setMe(me(role));
    render(
      <RequirePermission perm={perm as keyof Permissions}>
        <button type="button">Go</button>
      </RequirePermission>
    );
    const button = screen.getByRole('button', { name: 'Go' });
    expect(button).toHaveProperty('disabled', disabled);
    if (disabled) {
      const needed = ['editSessions', 'deleteSessions', 'changeLimits', 'sendCommands'].includes(perm) ? 'ADMIN' : 'OPERATOR';
      expect(button).toHaveAttribute('title', `Needs ${needed}`);
    }
  });

  it('leaves the child alone while the identity is unknown, and keeps its own disabled', () => {
    render(
      <RequirePermission perm="startRuns">
        <button type="button" disabled>
          Go
        </button>
      </RequirePermission>
    );
    expect(screen.getByRole('button', { name: 'Go' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Go' })).not.toHaveAttribute('title');
  });

  it('stops a disabled link from navigating', async () => {
    setMe(me('VIEWER'));
    render(
      <RequirePermission perm="editSessions">
        <a href="#elsewhere">Go</a>
      </RequirePermission>
    );
    await userEvent.setup().click(screen.getByText('Go'));
    expect(window.location.hash).toBe('');
    expect(screen.getByText('Go')).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('loadMe', () => {
  it('stores the identity, and forgets it when the server cannot say', async () => {
    reply = HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data: me('OPERATOR'), path: '/' });
    await loadMe();
    expect(knownRole()).toBe('OPERATOR');

    reply = HttpResponse.json({ message: 'none' }, { status: 404 });
    await loadMe();
    expect(knownRole()).toBeNull();
  });
});

describe('IdentityChip', () => {
  it('shows nothing while unknown or when no key is needed', () => {
    const { container, rerender } = render(<IdentityChip />);
    expect(container).toBeEmptyDOMElement();
    act(() => setMe(me('ADMIN', { authEnabled: false, keyName: null })));
    rerender(<IdentityChip />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the key and role, and offers to change the key', async () => {
    const required = () => (seen += 1);
    let seen = 0;
    window.addEventListener(API_KEY_REQUIRED_EVENT, required);
    setMe(me('OPERATOR'));
    const user = userEvent.setup();
    render(<IdentityChip />);

    const trigger = screen.getByRole('button', { name: 'Account' });
    expect(trigger).toHaveTextContent('alice');
    expect(trigger).toHaveTextContent('OPERATOR');
    await user.click(trigger);
    await user.click(await screen.findByRole('menuitem', { name: 'Change API key' }));
    expect(seen).toBe(1);
    window.removeEventListener(API_KEY_REQUIRED_EVENT, required);
  });

  it('signs out by forgetting the key', async () => {
    setApiKey('secret');
    setMe(me('VIEWER'));
    const reload = { reload: () => undefined };
    Object.defineProperty(window, 'location', { value: { ...window.location, reload: () => (reload.reload = () => 'called') }, configurable: true });
    const user = userEvent.setup();
    render(<IdentityChip />);
    await user.click(screen.getByRole('button', { name: 'Account' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    expect(getApiKey()).toBeNull();
  });
});
