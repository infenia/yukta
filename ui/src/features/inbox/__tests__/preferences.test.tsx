// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import { readDevicePrefs } from '../devicePrefs';
import { PreferencesSheet } from '../PreferencesSheet';
import { requestDesktopPermission } from '../useDesktopNotifications';

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  getPreferences: vi.fn(),
  savePreferences: vi.fn(),
}));

const KEY = 'yukta.notifications.device';
let permission: NotificationPermission = 'default';
const ask = vi.fn(async () => permission);

const open = async (sessions = ['s1', 's2']) => {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <PreferencesSheet sessions={sessions} />
    </QueryClientProvider>
  );
  await userEvent.click(screen.getByRole('button', { name: 'Preferences' }));
};

beforeEach(() => {
  permission = 'default';
  ask.mockClear();
  vi.stubGlobal(
    'Notification',
    Object.defineProperties(function () {}, {
      permission: { get: () => permission },
      requestPermission: { value: ask },
    })
  );
  vi.mocked(api.getPreferences).mockResolvedValue({
    levels: { 'run.failed': 'OFF' },
    mutedSessions: ['gone'],
  });
  vi.mocked(api.savePreferences).mockResolvedValue({
    levels: {},
    mutedSessions: [],
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('PreferencesSheet', () => {
  it('shows the levels, never offers off for input, and saves levels and muted sessions', async () => {
    await open();
    const input = await screen.findByLabelText('input');
    expect(
      Array.from((input as HTMLSelectElement).options).map((o) => o.value)
    ).toEqual(['INBOX', 'DESKTOP']);
    expect(screen.getByLabelText('run.failed')).toHaveValue('OFF');

    await userEvent.selectOptions(input, 'DESKTOP');
    await userEvent.click(screen.getByRole('checkbox', { name: 's1' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'gone' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 's2' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 's2' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(api.savePreferences).toHaveBeenCalledWith({
        levels: { 'run.failed': 'OFF', input: 'DESKTOP' },
        mutedSessions: ['s1'],
      })
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('says so when the preferences cannot be read', async () => {
    vi.mocked(api.getPreferences).mockRejectedValue(new Error('down'));
    await open([]);
    expect(
      await screen.findByText('The preferences could not be read.')
    ).toBeInTheDocument();
  });

  it('shows the error of a failed save and says when there are no sessions', async () => {
    vi.mocked(api.savePreferences).mockRejectedValue(
      new Error('Unknown category')
    );
    vi.mocked(api.getPreferences).mockResolvedValue({
      levels: {},
      mutedSessions: [],
    });
    await open([]);
    expect(await screen.findByText('No sessions yet.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Unknown category'
    );
  });

  it('asks for the permission only from the switch, and remembers the answer on this device', async () => {
    await open();
    await screen.findByLabelText('input');
    expect(ask).not.toHaveBeenCalled();

    permission = 'granted';
    const desktop = screen.getByRole('checkbox', {
      name: 'Show desktop notifications',
    });
    await userEvent.click(desktop);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(readDevicePrefs().desktop).toBe(true);

    await userEvent.click(desktop);
    expect(readDevicePrefs().desktop).toBe(false);
    expect(ask).toHaveBeenCalledTimes(1);

    await userEvent.click(
      screen.getByRole('checkbox', { name: /Show the title/ })
    );
    expect(readDevicePrefs().details).toBe(false);
  });

  it('explains a refused permission and keeps the switch off', async () => {
    await open();
    await screen.findByLabelText('input');
    permission = 'denied';
    await userEvent.click(
      screen.getByRole('checkbox', { name: 'Show desktop notifications' })
    );
    expect(await screen.findByRole('status')).toHaveTextContent(
      'blocked for this site'
    );
    expect(readDevicePrefs().desktop).toBe(false);
  });

  it('says when the browser has no notifications', async () => {
    vi.stubGlobal('Notification', undefined);
    await open();
    await screen.findByLabelText('input');
    expect(screen.getByRole('status')).toHaveTextContent(
      'cannot show desktop notifications'
    );
    await userEvent.click(
      screen.getByRole('checkbox', { name: 'Show desktop notifications' })
    );
    expect(readDevicePrefs().desktop).toBe(false);
    expect(await requestDesktopPermission()).toBe('unsupported');
  });

  it('works when storage is not available', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readDevicePrefs()).toEqual({ desktop: false, details: true });
    await open();
    await screen.findByLabelText('input');
    const details = screen.getByRole('checkbox', { name: /Show the title/ });
    await userEvent.click(details);
    expect(details).not.toBeChecked();
  });

  it('reads a stored choice', () => {
    localStorage.setItem(KEY, JSON.stringify({ desktop: true }));
    expect(readDevicePrefs()).toEqual({ desktop: true, details: true });
  });
});
