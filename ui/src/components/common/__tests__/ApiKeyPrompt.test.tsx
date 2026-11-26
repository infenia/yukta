// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  dismissApiKey,
  getApiKey,
  requireApiKey,
  setApiKey,
} from '@/lib/apiKey';
import { setMe, type Me } from '@/lib/identity';
import { renderHook } from '@testing-library/react';
import { useApiKeyRequired } from '@/lib/apiKey';
import ApiKeyPrompt from '../ApiKeyPrompt';

afterEach(() => {
  dismissApiKey();
  setApiKey(null);
  setMe(null);
  vi.unstubAllGlobals();
});

const answer = (status: number, headers: Record<string, string> = {}) =>
  vi.fn().mockResolvedValue(new Response('{}', { status, headers }));

const enter = (value: string) => {
  fireEvent.change(screen.getByLabelText('API key'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
};

describe('ApiKeyPrompt', () => {
  it('checks the key against /api/me, then stores it and reloads', async () => {
    const reload = vi.fn();
    const fetchMock = answer(200);
    vi.stubGlobal('location', { ...window.location, reload });
    vi.stubGlobal('fetch', fetchMock);
    render(<ApiKeyPrompt />);
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

    enter(' secret ');

    await vi.waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenCalledWith('/api/me', {
      headers: { Authorization: 'Bearer secret' },
    });
    expect(getApiKey()).toBe('secret');
  });

  it.each([
    [answer(401), 'That key was not accepted. Check it and try again.'],
    [
      answer(429, { 'Retry-After': '42' }),
      'Too many failed attempts from this address. Try again in 42 seconds.',
    ],
    [
      answer(429),
      'Too many failed attempts from this address. Try again in 60 seconds.',
    ],
    [answer(500), 'The server answered 500. Try again.'],
    [
      vi.fn().mockRejectedValue(new TypeError('offline')),
      'Could not reach the server. Check that it is running, then try again.',
    ],
  ])(
    'keeps a refused key out and says why (%#)',
    async (fetchMock, message) => {
      vi.stubGlobal('fetch', fetchMock);
      render(<ApiKeyPrompt />);

      enter('bad');

      expect(await screen.findByRole('alert')).toHaveTextContent(message);
      expect(getApiKey()).toBeNull();
      // typing again clears the message
      fireEvent.change(screen.getByLabelText('API key'), {
        target: { value: 'bad2' },
      });
      expect(screen.queryByRole('alert')).toBeNull();
    }
  );

  it('offers to keep the current key only while it is accepted', () => {
    const { rerender } = render(<ApiKeyPrompt />);
    expect(
      screen.queryByRole('button', { name: 'Keep the current key' })
    ).toBeNull();

    act(() => setMe({ authenticated: true } as Me));
    rerender(<ApiKeyPrompt />);
    const flag = renderHook(() => useApiKeyRequired());
    act(() => requireApiKey());
    expect(flag.result.current).toBe(true);

    fireEvent.click(
      screen.getByRole('button', { name: 'Keep the current key' })
    );
    expect(flag.result.current).toBe(false);
  });

  it('opens with the reason the server refused, and then offers no way back', () => {
    act(() => setMe({ authenticated: true } as Me));
    act(() => requireApiKey('The key in use was not accepted.'));
    render(<ApiKeyPrompt />);

    expect(screen.getByRole('alert')).toHaveTextContent(
      'The key in use was not accepted.'
    );
    expect(
      screen.queryByRole('button', { name: 'Keep the current key' })
    ).toBeNull();
  });
});
