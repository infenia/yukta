// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AccountMenu from '../AccountMenu';
import { API_KEY_REQUIRED_EVENT, getApiKey, setApiKey } from '@/lib/apiKey';
import { setMe, type Me } from '@/lib/identity';
import { getTheme, setTheme } from '@/lib/theme';

const admin: Me = {
  authenticated: true,
  keyName: 'alice',
  role: 'ADMIN',
  sessions: null,
  authEnabled: true,
  permissions: {
    startRuns: true,
    controlRuns: true,
    editSessions: true,
    deleteSessions: true,
    changeLimits: true,
    sendCommands: true,
  },
};

const open = async () => {
  const user = userEvent.setup();
  render(<AccountMenu />);
  await user.click(screen.getByRole('button', { name: 'Account and theme' }));
  return user;
};

describe('AccountMenu', () => {
  afterEach(() => {
    setMe(null);
    setApiKey(null);
    setTheme('system');
  });

  it('picks the theme, and has no account part without API keys', async () => {
    const user = await open();
    expect(
      screen.queryByRole('menuitem', { name: 'Sign out' })
    ).not.toBeInTheDocument();
    await user.click(
      await screen.findByRole('menuitemradio', { name: 'Dark' })
    );
    expect(getTheme()).toBe('dark');
  });

  it('shows the key and role, changes the key and signs out', async () => {
    setMe(admin);
    setApiKey('secret');
    const required = vi.fn();
    window.addEventListener(API_KEY_REQUIRED_EVENT, required);
    const reload = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload },
      configurable: true,
    });

    const user = await open();
    expect(await screen.findByText('alice · ADMIN')).toBeInTheDocument();
    await user.click(screen.getByRole('menuitem', { name: 'Change API key' }));
    expect(required).toHaveBeenCalledOnce();

    await user.click(screen.getByRole('button', { name: 'Account and theme' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    expect(getApiKey()).toBeNull();
    expect(reload).toHaveBeenCalledOnce();
    window.removeEventListener(API_KEY_REQUIRED_EVENT, required);
  });
});
