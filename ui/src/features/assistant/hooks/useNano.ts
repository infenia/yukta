// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useEffect, useState } from 'react';
import {
  downloadNano,
  nanoAvailability,
  type NanoState,
} from '../lib/models/nano';

/** Whether the browser's built-in model is ready, and a way to start its download. */
export function useNano() {
  const [state, setState] = useState<NanoState>('checking');
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    void nanoAvailability().then(setState);
  }, []);

  /** Call from a click: Chrome only starts the download for a user gesture. */
  const download = useCallback(async () => {
    setState('downloading');
    setProgress(0);
    try {
      await downloadNano(setProgress);
      setState('available');
    } catch {
      setState(await nanoAvailability());
    }
  }, []);

  return { state, progress, download };
}
