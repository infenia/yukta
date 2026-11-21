// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useNano } from '../hooks/useNano';
import { nanoSession } from '../testFixtures';

afterEach(() => vi.unstubAllGlobals());

describe('useNano', () => {
  it('starts by checking, and finds a browser without the API', async () => {
    const { result } = renderHook(() => useNano());
    expect(result.current.state).toBe('checking');

    await waitFor(() => expect(result.current.state).toBe('unsupported'));
  });

  it('reports what the browser says about the model', async () => {
    vi.stubGlobal('LanguageModel', {
      availability: vi.fn().mockResolvedValue('downloadable'),
    });

    const { result } = renderHook(() => useNano());

    await waitFor(() => expect(result.current.state).toBe('downloadable'));
  });

  it('downloads the model, showing the progress, and is then available', async () => {
    let finish: () => void = () => {};
    vi.stubGlobal('LanguageModel', {
      availability: vi.fn().mockResolvedValue('downloadable'),
      create: vi.fn(
        (options: { monitor: (m: EventTarget) => void }) =>
          new Promise((resolve) => {
            const monitor = new EventTarget();
            options.monitor(monitor);
            monitor.dispatchEvent(
              Object.assign(new Event('downloadprogress'), { loaded: 0.4 })
            );
            finish = () => resolve(nanoSession());
          })
      ),
    });
    const { result } = renderHook(() => useNano());
    await waitFor(() => expect(result.current.state).toBe('downloadable'));

    act(() => {
      void result.current.download();
    });

    await waitFor(() => expect(result.current.state).toBe('downloading'));
    expect(result.current.progress).toBe(0.4);
    await act(async () => finish());
    expect(result.current.state).toBe('available');
  });

  it('asks the browser again when the download fails', async () => {
    const availability = vi
      .fn()
      .mockResolvedValueOnce('downloadable')
      .mockResolvedValueOnce('unavailable');
    vi.stubGlobal('LanguageModel', {
      availability,
      create: vi.fn().mockRejectedValue(new Error('no space')),
    });
    const { result } = renderHook(() => useNano());
    await waitFor(() => expect(result.current.state).toBe('downloadable'));

    await act(async () => result.current.download());

    expect(result.current.state).toBe('unavailable');
  });
});
