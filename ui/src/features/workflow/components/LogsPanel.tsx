// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Input } from '@/components/ui/input';
import { formatLogTimestamp } from '../lib/timeFormat';
import { cn } from '@/lib/utils';
import type { LogEntry } from '../types/workflow';

/** Fixed row height (leading-5) so only the visible window of up to 2,000 lines is in the DOM. */
const ROW_HEIGHT = 20;

function levelClass(level: string | null): string {
  switch (level) {
    case 'WARN':
      return 'text-amber-400 font-semibold';
    case 'ERROR':
      return 'text-red-400 font-semibold';
    case 'DEBUG':
      return 'text-neutral-500';
    default:
      return 'text-neutral-200';
  }
}

function formatLine(entry: LogEntry): string {
  return `${formatLogTimestamp(entry.timestamp)} ${entry.level ?? 'INFO'} [${entry.pluginId}] ${entry.message}`;
}

/**
 * The collapsible EXECUTION LOGS terminal: streamed log lines with level colouring,
 * client-side filtering and toggleable auto-scroll.
 */
export function LogsPanel({ entries, isStreaming }: { entries: LogEntry[]; isStreaming: boolean }) {
  const [collapsed, setCollapsed] = useState(false);
  const [filter, setFilter] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const bodyRef = useRef<HTMLDivElement>(null);

  const visibleEntries = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return entries;
    }
    return entries.filter((entry) => formatLine(entry).toLowerCase().includes(needle));
  }, [entries, filter]);

  const virtualizer = useVirtualizer({
    count: visibleEntries.length,
    getScrollElement: () => bodyRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 20,
    initialRect: { width: 0, height: 224 },
  });

  useEffect(() => {
    if (autoScroll && !collapsed && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [visibleEntries, autoScroll, collapsed]);

  return (
    <section
      className="border-t border-outline-variant bg-surface"
      data-testid="logs-panel"
      aria-label="Execution logs"
    >
      <div className="flex flex-wrap items-center gap-3 px-4 py-2 sm:h-11 sm:flex-nowrap sm:py-0">
        <button
          type="button"
          onClick={() => setCollapsed((value) => !value)}
          aria-label={collapsed ? 'Expand execution logs' : 'Collapse execution logs'}
          aria-expanded={!collapsed}
          className="flex size-6 items-center justify-center text-on-surface-variant transition-colors hover:text-on-surface"
        >
          <span className="material-symbols-outlined text-lg">
            {collapsed ? 'expand_less' : 'expand_more'}
          </span>
        </button>
        <span className="material-symbols-outlined text-base text-on-surface-variant">terminal</span>
        <h2 className="text-xs font-semibold tracking-wider text-on-surface">EXECUTION LOGS</h2>
        {isStreaming && (
          <span
            className="border border-primary px-2 py-0.5 text-[10px] font-semibold tracking-wider text-primary"
            data-testid="streaming-pill"
          >
            STREAMING
          </span>
        )}
        <div className="flex w-full flex-col gap-2 sm:ml-auto sm:w-auto sm:flex-row sm:items-center sm:gap-4">
          <div className="relative w-full sm:w-56">
            <span className="material-symbols-outlined pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-sm text-on-surface-variant">
              search
            </span>
            <Input
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder="Filter logs..."
              aria-label="Filter logs"
              className="h-7 w-full pl-8 text-xs"
            />
          </div>
          <label className="flex cursor-pointer items-center gap-2 text-[10px] font-semibold tracking-wider text-on-surface-variant">
            AUTO-SCROLL
            <button
              type="button"
              role="switch"
              aria-checked={autoScroll}
              aria-label="Toggle auto-scroll"
              onClick={() => setAutoScroll((value) => !value)}
              className={cn(
                'h-4 w-8 border transition-colors',
                autoScroll ? 'border-primary bg-primary' : 'border-outline bg-surface-container'
              )}
            >
              <span
                className={cn(
                  'block size-3 bg-surface transition-transform',
                  autoScroll ? 'translate-x-4' : 'translate-x-0.5'
                )}
              />
            </button>
          </label>
        </div>
      </div>
      {!collapsed && (
        <div
          ref={bodyRef}
          className="h-56 overflow-y-auto bg-black px-4 py-2 font-mono text-xs leading-5"
          data-testid="logs-body"
        >
          {visibleEntries.length === 0 ? (
            <p className="text-neutral-500">No log output.</p>
          ) : (
            <div className="relative" style={{ height: virtualizer.getTotalSize() }}>
              {virtualizer.getVirtualItems().map((row) => (
                <p
                  key={row.key}
                  className={cn('absolute left-0 w-full whitespace-pre', levelClass(visibleEntries[row.index].level))}
                  style={{ top: row.start, height: ROW_HEIGHT }}
                >
                  {formatLine(visibleEntries[row.index])}
                </p>
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
