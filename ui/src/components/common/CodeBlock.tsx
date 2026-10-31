// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';

/** Mono, scrollable block with a copy button, for JSON and usage patterns. */
export function CodeBlock({ code, label }: { code: string; label?: string }) {
  const [status, setStatus] = useState<'copied' | 'failed' | null>(null);
  const copy = () => {
    const done = (next: 'copied' | 'failed') => {
      setStatus(next);
      setTimeout(() => setStatus(null), 1500);
    };
    if (!navigator.clipboard) {
      done('failed');
      return;
    }
    navigator.clipboard.writeText(code).then(
      () => done('copied'),
      () => done('failed')
    );
  };
  return (
    <div className="border-border bg-muted/50 shadow-card relative overflow-hidden rounded-xl border">
      <button
        type="button"
        onClick={copy}
        aria-label={label ? `Copy ${label}` : 'Copy'}
        className="border-border bg-card/80 text-label-sm text-on-surface-variant transition-standard hover:bg-surface-container-high hover:text-on-surface absolute top-1.5 right-1.5 rounded-lg border px-2 py-1"
      >
        {status === 'copied'
          ? 'Copied'
          : status === 'failed'
            ? 'Copy failed'
            : 'Copy'}
      </button>
      <pre className="text-code-sm max-h-64 overflow-auto p-3 pr-16 wrap-break-word whitespace-pre-wrap">
        {code}
      </pre>
    </div>
  );
}
