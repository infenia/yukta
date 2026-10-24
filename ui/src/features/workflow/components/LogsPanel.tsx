// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Input } from '@/components/ui/input';
import { formatLogTimestamp } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import type { LogEntry } from '../types/workflow';

/** Fixed row height (leading-5) so only the visible window of the buffered lines is in the DOM. */
const ROW_HEIGHT = 20;

function levelClass(level: string | null): string {
  switch (level) {
    case 'WARN':
      return 'text-log-warn font-semibold';
    case 'ERROR':
      return 'text-log-error font-semibold';
    case 'DEBUG':
      return 'text-log-debug';
    default:
      return 'text-terminal-fg';
  }
}

function formatLine(entry: LogEntry): string {
  return `${formatLogTimestamp(entry.timestamp)} ${entry.level ?? 'INFO'} [${entry.pluginId}] ${entry.message}`;
}

function LogLine({ entry }: { entry: LogEntry }) {
  return (
    <>
      <span className="text-log-debug">
        {formatLogTimestamp(entry.timestamp)}
      </span>{' '}
      <span className="inline-block w-12 font-semibold">
        {entry.level ?? 'INFO'}
      </span>{' '}
      <span className="text-log-info">[{entry.pluginId}]</span>{' '}
      <span className={levelClass(entry.level)}>{entry.message}</span>
    </>
  );
}

/**
 * The collapsible EXECUTION LOGS terminal: streamed log lines with level colouring,
 * client-side filtering and toggleable auto-scroll.
 */
export function LogsPanel({
  entries,
  isStreaming,
  dropped = 0,
}: {
  entries: LogEntry[];
  isStreaming: boolean;
  /** Earlier lines no longer buffered. */
  dropped?: number;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [filter, setFilter] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const [wrap, setWrap] = useState(false);
  const [copyStatus, setCopyStatus] = useState<'copied' | 'failed' | null>(
    null
  );
  const bodyRef = useRef<HTMLDivElement>(null);

  const visibleEntries = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return entries;
    }
    return entries.filter((entry) =>
      formatLine(entry).toLowerCase().includes(needle)
    );
  }, [entries, filter]);

  const virtualizer = useVirtualizer({
    count: visibleEntries.length,
    getScrollElement: () => bodyRef.current,
    measureElement: (el) => el.getBoundingClientRect().height || ROW_HEIGHT,
    estimateSize: () => ROW_HEIGHT,
    overscan: 20,
    initialRect: { width: 0, height: 224 },
  });

  useEffect(() => {
    virtualizer.measure();
  }, [wrap, virtualizer]);

  const copyLogs = () => {
    const done = (next: 'copied' | 'failed') => {
      setCopyStatus(next);
      setTimeout(() => setCopyStatus(null), 1500);
    };
    if (!navigator.clipboard) {
      done('failed');
      return;
    }
    navigator.clipboard
      .writeText(visibleEntries.map(formatLine).join('\n'))
      .then(
        () => done('copied'),
        () => done('failed')
      );
  };

  // turning auto-scroll on scrolls to the end (the effect below)
  const jumpToLatest = () => setAutoScroll(true);

  useEffect(() => {
    if (autoScroll && !collapsed && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [visibleEntries, autoScroll, collapsed]);

  return (
    <section
      className="border-outline-variant bg-surface border-t"
      data-testid="logs-panel"
      aria-label="Execution logs"
    >
      <div className="flex flex-wrap items-center gap-3 px-4 py-2 sm:h-11 sm:flex-nowrap sm:py-0">
        <button
          type="button"
          onClick={() => setCollapsed((value) => !value)}
          aria-label={
            collapsed ? 'Expand execution logs' : 'Collapse execution logs'
          }
          aria-expanded={!collapsed}
          className="text-on-surface-variant hover:text-on-surface flex size-6 items-center justify-center transition-colors"
        >
          <span className="material-symbols-outlined text-lg">
            {collapsed ? 'expand_less' : 'expand_more'}
          </span>
        </button>
        <span className="material-symbols-outlined text-on-surface-variant text-base">
          terminal
        </span>
        <h2 className="text-label-md text-on-surface">Execution logs</h2>
        {isStreaming && (
          <span
            className="border-primary/40 bg-primary/10 text-label-sm text-primary inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 tracking-wider"
            data-testid="streaming-pill"
          >
            <span
              aria-hidden
              className="live-dot bg-attention size-1.5 rounded-full"
            />
            STREAMING
          </span>
        )}
        {dropped > 0 && (
          <span
            className="border-log-warn/40 bg-log-warn/10 text-label-sm text-log-warn rounded-full border px-2.5 py-0.5 tracking-wider"
            data-testid="dropped-pill"
          >
            {dropped.toLocaleString()} EARLIER LINES HIDDEN
          </span>
        )}
        <div className="flex w-full flex-col gap-2 sm:ml-auto sm:w-auto sm:flex-row sm:items-center sm:gap-4">
          <div className="relative w-full sm:w-56">
            <span className="material-symbols-outlined text-on-surface-variant pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-sm">
              search
            </span>
            <Input
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder="Filter logs..."
              aria-label="Filter logs"
              className="text-body-sm h-7 w-full pl-8"
            />
          </div>
          <button
            type="button"
            onClick={() => setWrap((value) => !value)}
            aria-pressed={wrap}
            className={cn(
              'text-label-sm transition-standard rounded-lg border px-2 py-1 tracking-wider',
              wrap
                ? 'border-primary text-primary'
                : 'border-outline-variant text-on-surface-variant hover:bg-surface-container-high'
            )}
          >
            WRAP
          </button>
          <button
            type="button"
            onClick={copyLogs}
            className="text-label-sm transition-standard border-outline-variant text-on-surface-variant hover:bg-surface-container-high rounded-lg border px-2 py-1 tracking-wider"
          >
            {copyStatus === 'copied'
              ? 'COPIED'
              : copyStatus === 'failed'
                ? 'FAILED'
                : 'COPY'}
          </button>
          <label className="text-label-sm text-on-surface-variant flex cursor-pointer items-center gap-2 tracking-wider">
            AUTO-SCROLL
            <button
              type="button"
              role="switch"
              aria-checked={autoScroll}
              aria-label="Toggle auto-scroll"
              onClick={() => setAutoScroll((value) => !value)}
              className={cn(
                'h-4 w-8 border transition-colors',
                autoScroll
                  ? 'border-primary bg-primary'
                  : 'border-outline bg-surface-container'
              )}
            >
              <span
                className={cn(
                  'bg-surface block size-3 transition-transform',
                  autoScroll ? 'translate-x-4' : 'translate-x-0.5'
                )}
              />
            </button>
          </label>
        </div>
      </div>
      {!collapsed && (
        <div className="relative">
          <div
            ref={bodyRef}
            className="bg-terminal text-code-sm h-56 overflow-y-auto px-4 py-2 leading-5"
            data-testid="logs-body"
          >
            {visibleEntries.length === 0 ? (
              <p className="text-log-debug">No log output.</p>
            ) : (
              <div
                className="relative"
                style={{ height: virtualizer.getTotalSize() }}
              >
                {virtualizer.getVirtualItems().map((row) => (
                  <p
                    key={row.key}
                    ref={virtualizer.measureElement}
                    data-index={row.index}
                    className={cn(
                      'text-terminal-fg absolute left-0 w-full',
                      wrap ? 'break-all whitespace-pre-wrap' : 'whitespace-pre'
                    )}
                    style={{ top: row.start, minHeight: ROW_HEIGHT }}
                  >
                    <LogLine entry={visibleEntries[row.index]} />
                  </p>
                ))}
              </div>
            )}
          </div>
          {!autoScroll && visibleEntries.length > 0 && (
            <button
              type="button"
              onClick={jumpToLatest}
              className="text-label-sm bg-primary text-on-primary absolute right-4 bottom-3 rounded-lg px-3 py-1"
            >
              Jump to latest
            </button>
          )}
        </div>
      )}
    </section>
  );
}
