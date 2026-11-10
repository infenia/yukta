// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useCallback, useEffect, useRef, useState } from 'react';

/** How long the "Copied" confirmation stays. */
const CONFIRMATION_MS = 1500;

export type CopyStatus = 'copied' | 'failed' | null;

/** The look of a copy control for a status: green once copied, red when it failed. */
export const copyTone = (status: CopyStatus) =>
  status === 'copied'
    ? 'bg-success/10 text-success'
    : status === 'failed'
      ? 'bg-destructive/10 text-destructive'
      : '';

/** The icon of a copy control for a status. */
export const copyIcon = (status: CopyStatus, idle = 'content_copy') =>
  status === 'copied' ? 'check' : status === 'failed' ? 'error' : idle;

/** Copies text to the clipboard and reports `copied` or `failed` for a moment. */
export function useCopyCommand() {
  const [status, setStatus] = useState<CopyStatus>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = useCallback((text: string) => {
    const done = (next: 'copied' | 'failed') => {
      setStatus(next);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setStatus(null), CONFIRMATION_MS);
    };
    if (!navigator.clipboard) {
      done('failed');
      return;
    }
    navigator.clipboard.writeText(text).then(
      () => done('copied'),
      () => done('failed')
    );
  }, []);

  return { status, copy };
}
