// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettings } from '../hooks/useSettings';
import { buildModel } from '../lib/models';
import {
  DEFAULT_SETTINGS,
  readKey,
  readSettings,
  writeKey,
  writeSettings,
} from '../lib/settings';

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
afterEach(() => vi.restoreAllMocks());

describe('settings', () => {
  it('reads the defaults, then what was written over them', () => {
    expect(readSettings()).toEqual(DEFAULT_SETTINGS);

    writeSettings({ ...DEFAULT_SETTINGS, model: 'm', hidden: true });

    expect(readSettings()).toMatchObject({ model: 'm', hidden: true });
  });

  it('keeps the key for the tab, or on the device when asked', () => {
    expect(readKey()).toBeNull();

    writeKey('tab', false);
    expect(sessionStorage.getItem('yukta.assistant.key')).toBe('tab');
    expect(localStorage.getItem('yukta.assistant.key')).toBeNull();
    expect(readKey()).toBe('tab');

    writeKey('device', true);
    expect(sessionStorage.getItem('yukta.assistant.key')).toBeNull();
    expect(readKey()).toBe('device');
  });

  it('carries on when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    expect(readSettings()).toEqual(DEFAULT_SETTINGS);
    expect(readKey()).toBeNull();
    expect(() => writeSettings(DEFAULT_SETTINGS)).not.toThrow();
    expect(() => writeKey('k', true)).not.toThrow();
  });
});

describe('useSettings', () => {
  it('updates one setting and saves a verified setup with its key', () => {
    const { result } = renderHook(() => useSettings());
    expect(result.current.key).toBeNull();

    act(() => result.current.update({ hidden: true }));
    expect(result.current.settings.hidden).toBe(true);
    expect(readSettings().hidden).toBe(true);

    act(() =>
      result.current.save(
        { ...DEFAULT_SETTINGS, remember: true, model: 'z' },
        'sk'
      )
    );
    expect(result.current.key).toBe('sk');
    expect(result.current.settings).toMatchObject({
      model: 'z',
      hidden: false,
    });
    expect(localStorage.getItem('yukta.assistant.key')).toBe('sk');
  });
});

describe('buildModel', () => {
  it('needs a key and a model', () => {
    expect(buildModel(DEFAULT_SETTINGS, null)).toBeNull();
    expect(buildModel({ ...DEFAULT_SETTINGS, model: '' }, 'k')).toBeNull();
  });

  it('builds the provider the settings name', () => {
    expect(buildModel(DEFAULT_SETTINGS, 'k')?.label).toBe(
      'claude-sonnet-5-5 · your key'
    );
    expect(
      buildModel(
        {
          ...DEFAULT_SETTINGS,
          provider: 'openai',
          model: 'g',
          baseUrl: 'http://h',
        },
        'k'
      )?.label
    ).toBe('g · your key');
    expect(
      buildModel({ ...DEFAULT_SETTINGS, provider: 'openai', model: 'g' }, 'k')
        ?.label
    ).toBe('g · your key');
  });
});
