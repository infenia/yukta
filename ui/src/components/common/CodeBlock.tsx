// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';

/** Mono, scrollable block with a copy button, for JSON and usage patterns. */
export function CodeBlock({ code, label }: { code: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div className="border-outline-variant bg-surface-container-low relative overflow-hidden rounded-lg border">
      <button
        type="button"
        onClick={copy}
        aria-label={label ? `Copy ${label}` : 'Copy'}
        className="text-label-sm transition-standard text-on-surface-variant hover:bg-surface-container-high absolute top-1 right-1 px-2 py-1"
      >
        {copied ? 'Copied' : 'Copy'}
      </button>
      <pre className="text-code-sm max-h-64 overflow-auto p-3 pr-16 break-words whitespace-pre-wrap">
        {code}
      </pre>
    </div>
  );
}
