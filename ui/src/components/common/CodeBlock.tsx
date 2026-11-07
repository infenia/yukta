// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, useState, type ReactNode } from 'react';

const JSON_TOKEN =
  /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

/** Wraps JSON keys, strings, numbers and literals in token-colored spans; other text passes through. */
function highlightJson(code: string): ReactNode {
  try {
    JSON.parse(code);
  } catch {
    return code;
  }
  const out: ReactNode[] = [];
  let last = 0;
  for (const m of code.matchAll(JSON_TOKEN)) {
    const [text, str, colon, literal] = m;
    const tone = str
      ? colon
        ? 'text-primary'
        : 'text-success'
      : literal
        ? 'text-muted-foreground font-medium'
        : 'text-attention';
    out.push(code.slice(last, m.index));
    out.push(
      <Fragment key={m.index}>
        <span className={tone}>{colon ? str : text}</span>
        {colon}
      </Fragment>
    );
    last = m.index + text.length;
  }
  out.push(code.slice(last));
  return out;
}

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
        {highlightJson(code)}
      </pre>
    </div>
  );
}
