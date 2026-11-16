// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';

/** The two switches that belong to this browser, not to the person. */
export interface DevicePrefs {
  /** Raise operating system notifications from this browser. */
  desktop: boolean;
  /** Show the title and the workflow; off shows only "Yukta needs your input". */
  details: boolean;
}

const KEY = 'yukta.notifications.device';
const DEFAULTS: DevicePrefs = { desktop: false, details: true };

/** Storage can be missing or throw (private window, blocked site data): the defaults then. */
export function readDevicePrefs(): DevicePrefs {
  try {
    const raw = window.localStorage.getItem(KEY);
    return { ...DEFAULTS, ...(raw ? (JSON.parse(raw) as DevicePrefs) : {}) };
  } catch {
    return DEFAULTS;
  }
}

function write(prefs: DevicePrefs) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    // the switch holds for this page only
  }
}

export function useDevicePrefs() {
  const [prefs, setPrefs] = useState(readDevicePrefs);
  const update = useCallback((change: Partial<DevicePrefs>) => {
    setPrefs((current) => {
      const next = { ...current, ...change };
      write(next);
      return next;
    });
  }, []);
  return [prefs, update] as const;
}
