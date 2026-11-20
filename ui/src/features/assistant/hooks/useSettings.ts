// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useState } from 'react';
import {
  readKey,
  readSettings,
  writeKey,
  writeSettings,
  type Settings,
} from '../lib/settings';

/** The assistant's settings and provider key, kept in this browser. */
export function useSettings() {
  const [settings, setSettings] = useState(readSettings);
  const [key, setKey] = useState(readKey);

  const update = useCallback((change: Partial<Settings>) => {
    setSettings((current) => {
      const next = { ...current, ...change };
      writeSettings(next);
      return next;
    });
  }, []);

  /** Saves a verified setup in one step: the settings and the key, kept as the person chose. */
  const save = useCallback(
    (next: Settings, newKey: string) => {
      update(next);
      writeKey(newKey, next.remember);
      setKey(newKey);
    },
    [update]
  );

  return { settings, key, update, save };
}
