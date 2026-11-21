// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, type ReactNode } from 'react';
import { Tip } from '@/components/ui/tooltip';
import {
  copyIcon,
  copyTone,
  useCopyCommand,
} from '@/features/workflow/hooks/useCopyCommand';
import { cn } from '@/lib/utils';

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

// a quoted string, an option (-X, --payload) or the command itself at the start
const SHELL_TOKEN =
  /'(?:'\\''|[^'])*'|"(?:\\.|[^"\\])*"|(?<=^|\s)--?[A-Za-z][\w-]*|^\S+/g;

/** A curl or yukta-cli command: command, options and strings colored like JSON; JSON bodies colored inside. */
function highlightShell(code: string): ReactNode {
  const out: ReactNode[] = [];
  let last = 0;
  for (const m of code.matchAll(SHELL_TOKEN)) {
    const [text] = m;
    out.push(code.slice(last, m.index));
    const inner = text.startsWith("'") ? text.slice(1, -1) : '';
    const json = highlightJson(inner);
    out.push(
      <Fragment key={m.index}>
        {typeof json !== 'string' ? (
          <>'{json}'</>
        ) : (
          <span
            className={
              text.startsWith('-')
                ? 'text-attention'
                : text.startsWith("'") || text.startsWith('"')
                  ? 'text-success'
                  : 'text-primary font-medium'
            }
          >
            {text}
          </span>
        )}
      </Fragment>
    );
    last = m.index + text.length;
  }
  out.push(code.slice(last));
  return out;
}

const highlight = (code: string): ReactNode =>
  /^(curl|yukta-cli)\s/.test(code) ? highlightShell(code) : highlightJson(code);

/** Mono, scrollable block with a copy button, for JSON and usage patterns. */
export function CodeBlock({
  code,
  label,
  grow = false,
  title,
  collapsible = false,
}: {
  code: string;
  label?: string;
  /** A header strip naming what the code is (a node ID), with the copy button in it. */
  title?: string;
  /** With a title: the header folds the code away, starting closed. */
  collapsible?: boolean;
  /** Take the height the content needs, for a place that scrolls as a whole. */
  grow?: boolean;
}) {
  const { status, copy } = useCopyCommand();
  const copyButton = (
    <Tip
      label={
        status === 'copied'
          ? 'Copied'
          : status === 'failed'
            ? 'Copy failed'
            : 'Copy'
      }
    >
      <button
        type="button"
        onClick={() => copy(code)}
        aria-label={label ? `Copy ${label}` : 'Copy'}
        className={cn(
          'border-border transition-standard flex size-7 items-center justify-center rounded-lg border',
          !title && 'absolute top-1.5 right-1.5',
          status
            ? copyTone(status)
            : 'bg-card/80 text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface'
        )}
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          {copyIcon(status)}
        </span>
        <span className="sr-only">
          {status === 'copied'
            ? 'Copied'
            : status === 'failed'
              ? 'Copy failed'
              : 'Copy'}
        </span>
      </button>
    </Tip>
  );
  const pre = (
    <pre
      className={cn(
        'text-code-sm overflow-auto p-3 wrap-break-word whitespace-pre-wrap',
        !title && 'pr-11',
        !grow && 'max-h-64'
      )}
    >
      {highlight(code)}
    </pre>
  );
  const frame =
    'border-border bg-muted/50 shadow-card relative overflow-hidden rounded-xl border';
  if (!title) {
    return (
      <div className={frame}>
        {copyButton}
        {pre}
      </div>
    );
  }
  const header = (
    <div
      className={cn(
        'border-border flex items-center justify-between gap-2 py-1.5 pr-1.5 pl-3',
        collapsible ? 'group-open/code:border-b' : 'border-b'
      )}
    >
      <span className="text-code-sm text-on-surface min-w-0 font-medium break-all">
        {title}
      </span>
      <span className="flex items-center gap-1">
        {copyButton}
        {collapsible && (
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-on-surface-variant transition-standard text-lg group-open/code:rotate-180"
          >
            expand_more
          </span>
        )}
      </span>
    </div>
  );
  return collapsible ? (
    <details className={cn(frame, 'group/code')}>
      <summary className="cursor-pointer list-none select-none [&::-webkit-details-marker]:hidden">
        {header}
      </summary>
      {pre}
    </details>
  ) : (
    <div className={frame}>
      {header}
      {pre}
    </div>
  );
}
