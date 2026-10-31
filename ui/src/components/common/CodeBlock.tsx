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
    <div className="relative overflow-hidden rounded-xl border border-border bg-muted/50 shadow-card">
      <button
        type="button"
        onClick={copy}
        aria-label={label ? `Copy ${label}` : 'Copy'}
        className="absolute top-1.5 right-1.5 rounded-lg border border-border bg-card/80 px-2 py-1 text-label-sm text-on-surface-variant transition-standard hover:bg-surface-container-high hover:text-on-surface"
      >
        {status === 'copied'
          ? 'Copied'
          : status === 'failed'
            ? 'Copy failed'
            : 'Copy'}
      </button>
      <pre className="max-h-64 overflow-auto p-3 pr-16 text-code-sm wrap-break-word whitespace-pre-wrap">
        {code}
      </pre>
    </div>
  );
}
